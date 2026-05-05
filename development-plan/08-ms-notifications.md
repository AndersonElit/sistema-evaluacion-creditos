# Paso 08 — ms-notifications: Consumer SQS, Persistencia e Email

## Objetivo
Implementar `ms-notifications` completo: scaffold, migración Flyway de `notifications_db`,
consumer SQS con `@Scheduled`, idempotencia por `evaluacion_id`, y
`EmailSenderService` usando AWS SES (LocalStack en dev).

## Prerrequisitos
- Paso 01 completado (`postgres-notifications` en puerto 5434 y LocalStack en 4566)
- Paso 09 (LocalStack) ejecutado antes de probar el consumer (las colas deben existir)
- Java 21+, Maven, jbang

## 1. Generar Scaffold

```bash
jbang scaffold/MavenHexagonalScaffold.java -n ms-notifications -m sqs-consumer
```

## 2. Migración Flyway — `notifications_db`

Crear en `infrastructure/entry-points/app/src/main/resources/db/migration/`:

### `V1__create_notifications.sql`
```sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TYPE tipo_notif   AS ENUM ('APROBADO', 'RECHAZADO');
CREATE TYPE estado_notif AS ENUM ('PENDIENTE', 'ENVIADO', 'FALLIDO');

CREATE TABLE notifications (
    id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluacion_id        UUID          NOT NULL,
    destinatario_email   VARCHAR(255)  NOT NULL,
    tipo_notificacion    tipo_notif    NOT NULL,
    estado               estado_notif  NOT NULL DEFAULT 'PENDIENTE',
    intentos             INTEGER       NOT NULL DEFAULT 0,
    enviado_en           TIMESTAMPTZ,
    mensaje_sqs_id       VARCHAR(255),
    creado_en            TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_estado
    ON notifications (estado)
    WHERE estado = 'PENDIENTE';

-- Garantía de idempotencia: una sola notificación por evaluación
CREATE UNIQUE INDEX idx_notifications_evaluacion_unique
    ON notifications (evaluacion_id);
```

## 3. Entidad JPA — `infrastructure/driven-adapters/postgres`

### `NotificationEntity.java`
```java
package com.msnotifications.postgres;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class NotificationEntity extends PanacheEntityBase {

    @Id
    public UUID id = UUID.randomUUID();

    @Column(name = "evaluacion_id", nullable = false)
    public UUID evaluacionId;

    @Column(name = "destinatario_email", nullable = false)
    public String destinatarioEmail;

    @Column(name = "tipo_notificacion", nullable = false)
    @Enumerated(EnumType.STRING)
    public TipoNotif tipoNotificacion;

    @Column(name = "estado", nullable = false)
    @Enumerated(EnumType.STRING)
    public EstadoNotif estado = EstadoNotif.PENDIENTE;

    @Column(name = "intentos", nullable = false)
    public int intentos = 0;

    @Column(name = "enviado_en")
    public Instant enviadoEn;

    @Column(name = "mensaje_sqs_id")
    public String mensajeSqsId;

    @Column(name = "creado_en", nullable = false)
    public Instant creadoEn = Instant.now();

    public enum TipoNotif { APROBADO, RECHAZADO }
    public enum EstadoNotif { PENDIENTE, ENVIADO, FALLIDO }

    public static boolean existsByEvaluacionIdAndEstado(UUID evalId, EstadoNotif estado) {
        return count("evaluacionId = ?1 AND estado = ?2", evalId, estado) > 0;
    }
}
```

## 4. Modelo del evento SQS — `domain/model`

### `EvaluacionCompletadaEvent.java`
```java
package com.msnotifications.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluacionCompletadaEvent(
    String evaluacionId,
    String cedula,
    String destinatarioEmail,
    String nombreSolicitante,
    String estadoFinal,
    BigDecimal montoSolicitado,
    String moneda,
    int plazoAnios,
    String fechaEvaluacion,
    String version
) {}
```

## 5. Email Sender — `infrastructure/driven-adapters/postgres` (o nuevo módulo ses)

### `EmailSenderService.java`
```java
package com.msnotifications.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;

import java.math.BigDecimal;

@ApplicationScoped
public class EmailSenderService {

    private static final Logger log = LoggerFactory.getLogger(EmailSenderService.class);

    @jakarta.inject.Inject
    SesClient sesClient;

    @ConfigProperty(name = "aws.ses.from.email", defaultValue = "noreply@banco.com")
    String fromEmail;

    public void enviar(String destinatario, String estadoFinal,
                       BigDecimal monto, String fecha) {
        String asunto = "APROBADO".equals(estadoFinal)
                ? "Su solicitud de crédito fue APROBADA ✓"
                : "Su solicitud de crédito fue RECHAZADA";

        String cuerpoHtml = generarPlantilla(estadoFinal, monto, fecha);

        try {
            sesClient.sendEmail(SendEmailRequest.builder()
                    .destination(d -> d.toAddresses(destinatario))
                    .message(m -> m
                            .subject(c -> c.data(asunto).charset("UTF-8"))
                            .body(b -> b.html(c -> c.data(cuerpoHtml).charset("UTF-8")))
                    )
                    .source(fromEmail)
                    .build());
            log.info("Email enviado a {}: {}", destinatario, estadoFinal);
        } catch (Exception e) {
            log.error("Error enviando email a {}: {}", destinatario, e.getMessage());
            throw new RuntimeException("Fallo en envío de email", e);
        }
    }

    private String generarPlantilla(String estado, BigDecimal monto, String fecha) {
        if ("APROBADO".equals(estado)) {
            return """
                <html><body style="font-family:Arial,sans-serif;padding:20px">
                  <h2 style="color:#27ae60">Felicitaciones — Su crédito fue APROBADO</h2>
                  <p>Su solicitud por <strong>$%,.2f USD</strong> ha sido aprobada.</p>
                  <p>Fecha: <strong>%s</strong></p>
                  <p>Un asesor se comunicará con usted para continuar el proceso.</p>
                  <hr><p style="color:#888;font-size:12px">Mensaje automático. No responder.</p>
                </body></html>
                """.formatted(monto, fecha);
        }
        return """
            <html><body style="font-family:Arial,sans-serif;padding:20px">
              <h2 style="color:#e74c3c">Su solicitud de crédito fue RECHAZADA</h2>
              <p>Lamentablemente su solicitud por <strong>$%,.2f USD</strong> no fue aprobada.</p>
              <p>Fecha: <strong>%s</strong></p>
              <p>Para más información comuníquese con su asesor.</p>
              <hr><p style="color:#888;font-size:12px">Mensaje automático. No responder.</p>
            </body></html>
            """.formatted(monto, fecha);
    }
}
```

## 6. Consumer SQS — `infrastructure/entry-points/sqs-consumer`

### `NotificationConsumer.java`
```java
package com.msnotifications.sqsconsumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msnotifications.model.EvaluacionCompletadaEvent;
import com.msnotifications.postgres.EmailSenderService;
import com.msnotifications.postgres.NotificationEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    @Inject SqsClient sqsClient;
    @Inject EmailSenderService emailSender;
    @Inject ObjectMapper objectMapper;

    @ConfigProperty(name = "sqs.queue.url")
    String queueUrl;

    @Scheduled(every = "20s", delayed = "30s")
    @Transactional
    public void procesarMensajes() {
        List<Message> messages = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(5)
                        .build()
        ).messages();

        if (messages.isEmpty()) return;

        log.info("Procesando {} mensajes de SQS", messages.size());

        for (Message msg : messages) {
            try {
                procesarMensaje(msg);
                eliminar(msg.receiptHandle());
            } catch (Exception e) {
                log.error("Error procesando mensaje {}: {}", msg.messageId(), e.getMessage());
                // No eliminar: SQS reintentará hasta maxReceiveCount (3) → DLQ
            }
        }
    }

    private void procesarMensaje(Message msg) throws Exception {
        EvaluacionCompletadaEvent evento = objectMapper.readValue(
                msg.body(), EvaluacionCompletadaEvent.class);

        UUID evalId = UUID.fromString(evento.evaluacionId());

        // Idempotencia: verificar si ya fue procesado
        if (NotificationEntity.existsByEvaluacionIdAndEstado(
                evalId, NotificationEntity.EstadoNotif.ENVIADO)) {
            log.info("Notificación ya enviada para evaluacion {} — ignorando", evalId);
            return;
        }

        // Persistir PENDIENTE
        NotificationEntity notif = new NotificationEntity();
        notif.evaluacionId = evalId;
        notif.destinatarioEmail = evento.destinatarioEmail();
        notif.tipoNotificacion = NotificationEntity.TipoNotif.valueOf(evento.estadoFinal());
        notif.mensajeSqsId = msg.messageId();
        NotificationEntity.persist(notif);

        // Enviar email
        emailSender.enviar(
                evento.destinatarioEmail(),
                evento.estadoFinal(),
                evento.montoSolicitado(),
                evento.fechaEvaluacion()
        );

        // Actualizar a ENVIADO
        notif.estado = NotificationEntity.EstadoNotif.ENVIADO;
        notif.enviadoEn = Instant.now();
        notif.intentos++;
    }

    private void eliminar(String receiptHandle) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(receiptHandle)
                .build());
    }
}
```

## 7. `application.properties`

```properties
quarkus.http.port=8083

# ── DataSource — notifications_db ────────────────────────────
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=${NOTIF_DB_USERNAME:postgres}
quarkus.datasource.password=${NOTIF_DB_PASSWORD:postgres}
quarkus.datasource.jdbc.url=jdbc:postgresql://${NOTIF_DB_HOST:localhost}:5434/notifications_db
quarkus.hibernate-orm.database.generation=validate
quarkus.flyway.migrate-at-start=true
quarkus.flyway.locations=classpath:db/migration

# ── AWS SQS ──────────────────────────────────────────────────
quarkus.sqs.aws.region=${AWS_REGION:us-east-1}
quarkus.sqs.endpoint-override=${SQS_ENDPOINT_URL:http://localhost:4566}
sqs.queue.url=${SQS_QUEUE_URL:http://localhost:4566/000000000000/credit-evaluation-notifications}

# ── AWS SES ──────────────────────────────────────────────────
quarkus.ses.aws.region=${AWS_REGION:us-east-1}
quarkus.ses.endpoint-override=${SES_ENDPOINT_URL:http://localhost:4566}
aws.ses.from.email=${SES_FROM_EMAIL:noreply@banco.com}

aws.accessKeyId=${AWS_ACCESS_KEY_ID:test}
aws.secretAccessKey=${AWS_SECRET_ACCESS_KEY:test}

# ── Health ───────────────────────────────────────────────────
quarkus.smallrye-health.root-path=/q/health
```

## 8. Levantar en modo dev

```bash
cd ms-notifications/infrastructure/entry-points/app
mvn quarkus:dev
```

## Verificación

```bash
# Health
curl -s http://localhost:8083/q/health | jq .status
# "UP"

# Publicar mensaje de prueba directamente en SQS (LocalStack)
aws --endpoint-url=http://localhost:4566 sqs send-message \
  --region us-east-1 \
  --queue-url http://localhost:4566/000000000000/credit-evaluation-notifications \
  --message-body '{
    "evaluacionId": "550e8400-e29b-41d4-a716-446655440001",
    "cedula": "1713175071",
    "destinatarioEmail": "test@email.com",
    "estadoFinal": "APROBADO",
    "montoSolicitado": 5000.00,
    "moneda": "USD",
    "plazoAnios": 3,
    "fechaEvaluacion": "2026-05-05T14:30:00Z",
    "version": "1.0"
  }' \
  --no-sign-request

# Esperar ~30s (delayed) + 20s (scheduler) y verificar en BD
psql -h localhost -p 5434 -U postgres -d notifications_db \
  -c "SELECT id, evaluacion_id, estado, enviado_en FROM notifications;"
# estado: ENVIADO

# Verificar idempotencia: enviar el mismo mensaje nuevamente
# El consumer debe ignorarlo y no crear un segundo registro
```

## Estado esperado al finalizar
- [ ] Flyway aplica `V1__create_notifications.sql` en `notifications_db`
- [ ] `@Scheduled(every="20s")` consumer activo y visible en logs
- [ ] Mensaje SQS de prueba procesado → estado `ENVIADO` en BD
- [ ] Email "enviado" vía LocalStack SES (log de confirmación)
- [ ] Mensaje duplicado ignorado (idempotencia por `evaluacion_id`)
- [ ] Mensaje malformado no procesado, permanece en cola para reintento
