# Sistema de Notificaciones — AWS SQS + AWS SES

## 1. Visión General

Tras completar una evaluación de crédito, el sistema notifica al solicitante por email (APROBADO o RECHAZADO) de forma **asíncrona** usando una cola SQS como buffer desacoplado y AWS SES como proveedor de envío.

---

## 2. Arquitectura de Notificaciones

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                  │
│  POST /v1/credit-evaluations                                     │
│         │                                                        │
│         ▼                                                        │
│  [ms-credit-evaluation Orquestador]                                              │
│         │                                                        │
│         ├── 1. Evalúa crédito (sync)                            │
│         ├── 2. Persiste en PostgreSQL (sync)                     │
│         ├── 3. Responde 201 al cliente (sync)    ← rápido       │
│         │                                                        │
│         └── 4. Publica mensaje en SQS (async, fire-and-forget)  │
│                        │                                         │
│                        ▼                                         │
│              ┌─────────────────────┐                             │
│              │   AWS SQS Queue     │                             │
│              │  credit-eval-notif  │                             │
│              │  (Standard Queue)   │                             │
│              └─────────────────────┘                             │
│                        │                                         │
│              ┌─────────┘  polling cada 20s                       │
│              ▼                                                   │
│  [Notification Worker]  (Quarkus Scheduler)                      │
│         │                                                        │
│         ├── 5. Lee mensaje de SQS                               │
│         ├── 6. Verifica idempotencia en BD                       │
│         ├── 7. Envía email via AWS SES                           │
│         ├── 8. Actualiza estado en BD (ENVIADO)                  │
│         └── 9. Elimina mensaje de SQS (ack)                      │
│                                                                  │
│  Si falla 3 veces → DLQ (Dead Letter Queue)                      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Configuración de Colas SQS

### Cola Principal: `credit-evaluation-notifications`

```json
{
  "QueueName": "credit-evaluation-notifications",
  "Attributes": {
    "MessageRetentionPeriod": "86400",
    "VisibilityTimeout": "60",
    "ReceiveMessageWaitTimeSeconds": "20",
    "RedrivePolicy": {
      "deadLetterTargetArn": "arn:aws:sqs:us-east-1:123456789:credit-eval-notif-dlq",
      "maxReceiveCount": "3"
    }
  }
}
```

| Parámetro | Valor | Razón |
|-----------|-------|-------|
| `VisibilityTimeout` | 60s | Tiempo para procesar sin que otro consumer lo vea |
| `WaitTimeSeconds` | 20s | Long polling: menos llamadas vacías, menor costo |
| `MessageRetentionPeriod` | 86400s (1 día) | Tiempo antes de descarte si no se procesa |
| `maxReceiveCount` | 3 | Intentos antes de mover a DLQ |

### Dead Letter Queue: `credit-eval-notif-dlq`

```json
{
  "QueueName": "credit-eval-notif-dlq",
  "Attributes": {
    "MessageRetentionPeriod": "1209600"
  }
}
```

La DLQ retiene mensajes fallidos por **14 días** para revisión manual o reintento.

---

## 4. Formato del Mensaje SQS

```json
{
  "evaluacionId": "550e8400-e29b-41d4-a716-446655440001",
  "cedula": "1713175071",
  "destinatarioEmail": "solicitante@email.com",
  "nombreSolicitante": "Juan Pérez",
  "estadoFinal": "APROBADO",
  "montoSolicitado": 5000.00,
  "moneda": "USD",
  "plazoAnios": 3,
  "fechaEvaluacion": "2026-05-05T14:30:00Z",
  "version": "1.0"
}
```

> El campo `version` permite evolucionar el esquema del mensaje sin romper el consumer.

---

## 5. Publicación desde ms-credit-evaluation

### Dependencias (`pom.xml`)

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>sqs</artifactId>
    <version>2.25.0</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

### `SqsNotificationPublisher.java`

```java
@ApplicationScoped
public class SqsNotificationPublisher {

    @Inject ObjectMapper objectMapper;

    private final SqsClient sqsClient;
    private final String queueUrl;

    @ConfigProperty(name = "aws.sqs.queue.url")
    String sqsQueueUrl;

    public SqsNotificationPublisher() {
        this.sqsClient = SqsClient.builder()
            .region(Region.US_EAST_1)
            .build();
    }

    public void publicar(EvaluacionCompletadaEvent evento) {
        try {
            String body = objectMapper.writeValueAsString(evento);

            SendMessageRequest request = SendMessageRequest.builder()
                .queueUrl(sqsQueueUrl)
                .messageBody(body)
                // MessageGroupId para FIFO (si se cambia a FIFO queue)
                .messageDeduplicationId(evento.getEvaluacionId().toString())
                .build();

            SendMessageResponse response = sqsClient.sendMessage(request);
            log.infof("Mensaje publicado en SQS: messageId=%s", response.messageId());

        } catch (Exception e) {
            // No falla la evaluación si SQS no está disponible
            log.errorf(e, "Error publicando en SQS para evaluación %s", evento.getEvaluacionId());
        }
    }
}
```

### Uso en `CreditEvaluationService.java`

```java
@Transactional
public EvaluacionCreditoResponse evaluar(SolicitudCreditoRequest request, String emailEvaluador) {
    // ... lógica de evaluación ...

    CreditEvaluation evaluation = persistir(result);

    // Publicar evento async (no bloqueante)
    EvaluacionCompletadaEvent evento = EvaluacionCompletadaEvent.builder()
        .evaluacionId(evaluation.getId())
        .cedula(request.getCedula())
        .destinatarioEmail(obtenerEmailPorCedula(request.getCedula()))
        .estadoFinal(evaluation.getEstadoFinal().name())
        .montoSolicitado(request.getMontoSolicitado())
        .fechaEvaluacion(evaluation.getFechaEvaluacion())
        .build();

    sqsPublisher.publicar(evento);

    return EvaluacionMapper.toResponse(evaluation);
}
```

---

## 6. Notification Worker (Consumer)

### `NotificationWorker.java`

```java
@ApplicationScoped
public class NotificationWorker {

    @Inject SqsClient sqsClient;
    @Inject EmailSenderService emailSender;
    @Inject NotificationRepository notificationRepo;
    @Inject ObjectMapper objectMapper;

    @ConfigProperty(name = "aws.sqs.queue.url")
    String queueUrl;

    @Scheduled(every = "20s", delayed = "30s")
    @Transactional
    public void procesarMensajes() {
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(10)
            .waitTimeSeconds(20)
            .build();

        List<Message> messages = sqsClient.receiveMessage(receiveRequest).messages();

        for (Message message : messages) {
            try {
                procesarMensaje(message);
                eliminarMensaje(message.receiptHandle());
            } catch (Exception e) {
                log.errorf(e, "Error procesando mensaje SQS: %s", message.messageId());
                // No eliminar: SQS reintentará automáticamente
            }
        }
    }

    private void procesarMensaje(Message message) throws Exception {
        EvaluacionCompletadaEvent evento = objectMapper.readValue(
            message.body(), EvaluacionCompletadaEvent.class);

        // Idempotencia: verificar si ya fue enviado
        if (notificationRepo.existsByEvaluacionIdAndEstado(
                evento.getEvaluacionId(), EstadoNotificacion.ENVIADO)) {
            log.infof("Notificación ya enviada para evaluación %s — ignorando duplicado",
                evento.getEvaluacionId());
            return;
        }

        // Persistir notificación en estado PENDIENTE
        Notification notif = crearNotificacion(evento, message.messageId());

        // Enviar email
        emailSender.enviar(
            evento.getDestinatarioEmail(),
            evento.getEstadoFinal(),
            evento.getMontoSolicitado(),
            evento.getFechaEvaluacion()
        );

        // Actualizar estado
        notif.setEstado(EstadoNotificacion.ENVIADO);
        notif.setEnviadoEn(Instant.now());
        notificationRepo.persist(notif);
    }

    private void eliminarMensaje(String receiptHandle) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
            .queueUrl(queueUrl)
            .receiptHandle(receiptHandle)
            .build());
    }
}
```

---

## 7. Envío de Email con AWS SES

### Dependencias

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>ses</artifactId>
    <version>2.25.0</version>
</dependency>
```

### `EmailSenderService.java`

```java
@ApplicationScoped
public class EmailSenderService {

    private final SesClient sesClient = SesClient.builder()
        .region(Region.US_EAST_1)
        .build();

    @ConfigProperty(name = "aws.ses.from.email")
    String fromEmail;

    public void enviar(String destinatario, String estado,
                       BigDecimal monto, Instant fecha) {

        String asunto = estado.equals("APROBADO")
            ? "✅ Su solicitud de crédito fue APROBADA"
            : "❌ Su solicitud de crédito fue RECHAZADA";

        String cuerpoHtml = generarPlantilla(estado, monto, fecha);

        SendEmailRequest request = SendEmailRequest.builder()
            .destination(d -> d.toAddresses(destinatario))
            .message(m -> m
                .subject(c -> c.data(asunto).charset("UTF-8"))
                .body(b -> b
                    .html(c -> c.data(cuerpoHtml).charset("UTF-8"))
                )
            )
            .source(fromEmail)
            .build();

        sesClient.sendEmail(request);
    }

    private String generarPlantilla(String estado, BigDecimal monto, Instant fecha) {
        if ("APROBADO".equals(estado)) {
            return """
                <html>
                <body style="font-family: Arial, sans-serif; padding: 20px;">
                  <h2 style="color: #27ae60;">✅ ¡Felicitaciones! Su crédito fue APROBADO</h2>
                  <p>Su solicitud de crédito por <strong>$%,.2f USD</strong> ha sido aprobada.</p>
                  <p>Fecha de evaluación: <strong>%s</strong></p>
                  <p>Un asesor se pondrá en contacto con usted para continuar el proceso.</p>
                  <hr>
                  <p style="color: #888; font-size: 12px;">
                    Este es un mensaje automático. No responda a este correo.
                  </p>
                </body>
                </html>
                """.formatted(monto, fecha.toString());
        } else {
            return """
                <html>
                <body style="font-family: Arial, sans-serif; padding: 20px;">
                  <h2 style="color: #e74c3c;">❌ Su solicitud de crédito fue RECHAZADA</h2>
                  <p>Lamentablemente su solicitud por <strong>$%,.2f USD</strong> no pudo ser aprobada
                     en este momento.</p>
                  <p>Fecha de evaluación: <strong>%s</strong></p>
                  <p>Para más información, comuníquese con su asesor.</p>
                  <hr>
                  <p style="color: #888; font-size: 12px;">
                    Este es un mensaje automático. No responda a este correo.
                  </p>
                </body>
                </html>
                """.formatted(monto, fecha.toString());
        }
    }
}
```

---

## 8. Configuración (`application.properties`)

```properties
# ── AWS SQS ──────────────────────────────────────────────────
aws.sqs.queue.url=${SQS_QUEUE_URL:http://localhost:4566/000000000000/credit-evaluation-notifications}
aws.region=${AWS_REGION:us-east-1}

# ── AWS SES ──────────────────────────────────────────────────
aws.ses.from.email=${SES_FROM_EMAIL:noreply@banco.com}

# ── AWS Credentials (usar IAM Role en producción) ─────────────
aws.accessKeyId=${AWS_ACCESS_KEY_ID:test}
aws.secretAccessKey=${AWS_SECRET_ACCESS_KEY:test}

# ── LocalStack para desarrollo ───────────────────────────────
%dev.quarkus.aws.sqs.endpoint-override=http://localhost:4566
%dev.quarkus.aws.ses.endpoint-override=http://localhost:4566
```

---

## 9. LocalStack para Desarrollo Local

```yaml
# docker-compose.yml (fragmento)
localstack:
  image: localstack/localstack:3.0
  ports:
    - "4566:4566"
  environment:
    SERVICES: sqs,ses
    DEFAULT_REGION: us-east-1
  volumes:
    - ./scripts/localstack-init.sh:/etc/localstack/init/ready.d/init.sh
```

```bash
# scripts/localstack-init.sh
#!/bin/bash
# Crear colas SQS
awslocal sqs create-queue --queue-name credit-eval-notif-dlq
awslocal sqs create-queue --queue-name credit-evaluation-notifications \
  --attributes '{
    "VisibilityTimeout": "60",
    "RedrivePolicy": "{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:000000000000:credit-eval-notif-dlq\",\"maxReceiveCount\":\"3\"}"
  }'

# Verificar email sender en SES
awslocal ses verify-email-identity --email-address noreply@banco.com
echo "LocalStack SQS + SES inicializado"
```

---

## 10. Política IAM para el Microservicio (Producción)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SQSPublish",
      "Effect": "Allow",
      "Action": [
        "sqs:SendMessage",
        "sqs:GetQueueUrl"
      ],
      "Resource": "arn:aws:sqs:us-east-1:*:credit-evaluation-notifications"
    },
    {
      "Sid": "SQSConsume",
      "Effect": "Allow",
      "Action": [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:ChangeMessageVisibility"
      ],
      "Resource": "arn:aws:sqs:us-east-1:*:credit-evaluation-notifications"
    },
    {
      "Sid": "SESSend",
      "Effect": "Allow",
      "Action": "ses:SendEmail",
      "Resource": "*",
      "Condition": {
        "StringEquals": {
          "ses:FromAddress": "noreply@banco.com"
        }
      }
    }
  ]
}
```

---

## 11. Observabilidad y Monitoreo

| Métrica | Herramienta | Alarma sugerida |
|---------|------------|-----------------|
| Mensajes en DLQ > 0 | CloudWatch | Alerta inmediata |
| Antigüedad mensajes > 30min | CloudWatch | Revisar worker |
| Tasa de error SES > 5% | CloudWatch | Revisar plantillas/dominio |
| Mensajes en cola > 100 | CloudWatch | Escalar workers |

```bash
# Comandos útiles de monitoreo
# Ver mensajes pendientes
aws sqs get-queue-attributes \
  --queue-url $QUEUE_URL \
  --attribute-names ApproximateNumberOfMessages

# Ver mensajes en DLQ
aws sqs get-queue-attributes \
  --queue-url $DLQ_URL \
  --attribute-names ApproximateNumberOfMessages
```
