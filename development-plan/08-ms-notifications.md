# Paso 08 — ms-notifications: Consumer SQS, Persistencia e Email

## Objetivo
Implementar `ms-notifications` completo respetando arquitectura hexagonal:
- `infrastructure/driven-adapters/postgres` → solo operaciones en BD (`NotificationEntity`)
- `infrastructure/driven-adapters/ses` → adaptador de envío de email (`EmailSenderService`)
- `application/use-cases` → caso de uso (`ProcesarNotificacionUseCase`) con la lógica de negocio
- `infrastructure/entry-points/sqs-consumer` → consumer SQS que delega al caso de uso

## Prerrequisitos
- Paso 01 completado (`postgres-notifications` en puerto 5434 y LocalStack en 4566)
- Paso 09 (LocalStack) ejecutado antes de probar el consumer (las colas deben existir)
- Java 21+, Maven, jbang

## 1. Generar Scaffold

```bash
cd backend/
jbang ../scaffold/MavenHexagonalScaffold.java -n ms-notifications -m sqs-consumer
```

## 2. Migración manual — `notifications_db`

Crear la carpeta y el archivo, luego aplicarlo **antes** de levantar el servicio:

```bash
mkdir -p infrastructure/entry-points/app/src/main/resources/db/migration

psql -h localhost -p 5435 -U postgres -d notifications_db \
  -f infrastructure/entry-points/app/src/main/resources/db/migration/V1__create_notifications.sql
```

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

## 3. Port y Adaptador de Persistencia — `application/use-cases` + `infrastructure/driven-adapters/postgres`

El use case depende del port (interfaz), no de la entidad JPA.
El adaptador implementa el port y encapsula todo detalle de Hibernate Reactive.

### `NotificationPort.java` — `application/use-cases`
```java
package com.msnotifications.usecase.port;

import io.smallrye.mutiny.Uni;
import java.util.UUID;

public interface NotificationPort {
    Uni<Boolean> existeEnviada(UUID evaluacionId);
    Uni<UUID> crear(UUID evaluacionId, String destinatarioEmail,
                    String tipoNotificacion, String mensajeSqsId);
    Uni<Void> marcarEnviada(UUID id);
}
```

### `NotificationEntity.java` — `infrastructure/driven-adapters/postgres`
```java
package com.msnotifications.postgres.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
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
}
```

### `NotificationAdapter.java` — `infrastructure/driven-adapters/postgres`
```java
package com.msnotifications.postgres.adapter;

import com.msnotifications.postgres.entity.NotificationEntity;
import com.msnotifications.usecase.port.NotificationPort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class NotificationAdapter implements NotificationPort {

    @Override
    public Uni<Boolean> existeEnviada(UUID evaluacionId) {
        return NotificationEntity.count(
                "evaluacionId = ?1 AND estado = ?2",
                evaluacionId, NotificationEntity.EstadoNotif.ENVIADO)
                .map(n -> n > 0);
    }

    @Override
    public Uni<UUID> crear(UUID evaluacionId, String destinatarioEmail,
                            String tipoNotificacion, String mensajeSqsId) {
        NotificationEntity notif = new NotificationEntity();
        notif.evaluacionId = evaluacionId;
        notif.destinatarioEmail = destinatarioEmail;
        notif.tipoNotificacion = NotificationEntity.TipoNotif.valueOf(tipoNotificacion);
        notif.mensajeSqsId = mensajeSqsId;
        return notif.<NotificationEntity>persist().map(n -> n.id);
    }

    @Override
    public Uni<Void> marcarEnviada(UUID id) {
        return NotificationEntity.<NotificationEntity>findById(id)
                .invoke(n -> {
                    n.estado = NotificationEntity.EstadoNotif.ENVIADO;
                    n.enviadoEn = Instant.now();
                    n.intentos++;
                })
                .replaceWithVoid();
    }
}
```

## 4. Modelo del evento SQS — `domain/model`

### `EvaluacionCompletadaEvent.java`
```java
package com.msnotifications.model.entity;

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

## 5. Email Sender Reactivo — `infrastructure/driven-adapters/ses`

Módulo dedicado para la integración con AWS SES. No mezcla responsabilidades con el adaptador de PostgreSQL.

### `EmailSenderService.java`
```java
package com.msnotifications.ses.adapter;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ses.SesAsyncClient;
import software.amazon.awssdk.services.ses.model.*;

import java.math.BigDecimal;

@ApplicationScoped
public class EmailSenderService {

    private static final Logger log = LoggerFactory.getLogger(EmailSenderService.class);

    @Inject
    SesAsyncClient sesClient;

    @ConfigProperty(name = "aws.ses.from.email", defaultValue = "noreply@banco.com")
    String fromEmail;

    public Uni<Void> enviar(String destinatario, String estadoFinal,
                             BigDecimal monto, String fecha) {
        String asunto = "APROBADO".equals(estadoFinal)
                ? "Su solicitud de crédito fue APROBADA ✓"
                : "Su solicitud de crédito fue RECHAZADA";
        String cuerpoHtml = generarPlantilla(estadoFinal, monto, fecha);

        return Uni.createFrom().completionStage(() ->
                sesClient.sendEmail(SendEmailRequest.builder()
                        .destination(d -> d.toAddresses(destinatario))
                        .message(m -> m
                                .subject(c -> c.data(asunto).charset("UTF-8"))
                                .body(b -> b.html(c -> c.data(cuerpoHtml).charset("UTF-8")))
                        )
                        .source(fromEmail)
                        .build()))
                .invoke(r -> log.info("Email enviado a {}: {}", destinatario, estadoFinal))
                .replaceWithVoid()
                .onFailure().invoke(e -> log.error("Error enviando email a {}: {}",
                        destinatario, e.getMessage()));
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

## 6. Caso de Uso — `application/use-cases`

Contiene la lógica de negocio: verificar idempotencia, persistir la entidad y delegar el envío al adaptador SES.

### `ProcesarNotificacionUseCase.java`
```java
package com.msnotifications.usecase;

import com.msnotifications.model.entity.EvaluacionCompletadaEvent;
import com.msnotifications.ses.adapter.EmailSenderService;
import com.msnotifications.usecase.port.NotificationPort;
import io.quarkus.hibernate.reactive.panache.common.ReactiveTransactional;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@ApplicationScoped
public class ProcesarNotificacionUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcesarNotificacionUseCase.class);

    @Inject
    NotificationPort notificationPort;

    @Inject
    EmailSenderService emailSender;

    @ReactiveTransactional
    public Uni<Void> procesar(EvaluacionCompletadaEvent evento, String mensajeSqsId) {
        UUID evalId = UUID.fromString(evento.evaluacionId());

        return notificationPort.existeEnviada(evalId)
                .chain(yaEnviado -> {
                    if (yaEnviado) {
                        log.info("Notificación ya enviada para evaluacion {} — ignorando", evalId);
                        return Uni.createFrom().voidItem();
                    }

                    return notificationPort.crear(
                                    evalId,
                                    evento.destinatarioEmail(),
                                    evento.estadoFinal(),
                                    mensajeSqsId)
                            .chain(notifId -> emailSender.enviar(
                                    evento.destinatarioEmail(),
                                    evento.estadoFinal(),
                                    evento.montoSolicitado(),
                                    evento.fechaEvaluacion())
                                    .chain(() -> notificationPort.marcarEnviada(notifId)));
                });
    }
}
```

## 7. Consumer SQS Reactivo — `infrastructure/entry-points/sqs-consumer`

Solo responsable de interactuar con SQS: recibir, deserializar, delegar al caso de uso y eliminar.

### `NotificationConsumer.java`
```java
package com.msnotifications.sqsconsumer.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msnotifications.model.entity.EvaluacionCompletadaEvent;
import com.msnotifications.usecase.ProcesarNotificacionUseCase;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;

@ApplicationScoped
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    @Inject SqsAsyncClient sqsClient;
    @Inject ProcesarNotificacionUseCase useCase;
    @Inject ObjectMapper objectMapper;

    @ConfigProperty(name = "sqs.queue.url")
    String queueUrl;

    @Scheduled(every = "20s", delayed = "30s")
    public Uni<Void> procesarMensajes() {
        return Uni.createFrom().completionStage(() ->
                sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(5)
                        .build()))
                .chain(response -> {
                    var messages = response.messages();
                    if (messages.isEmpty()) return Uni.createFrom().voidItem();

                    log.info("Procesando {} mensajes de SQS", messages.size());

                    return Multi.createFrom().iterable(messages)
                            .onItem().transformToUniAndConcatenate(this::procesarYEliminar)
                            .collect().asList()
                            .replaceWithVoid();
                });
    }

    Uni<Void> procesarYEliminar(Message msg) {
        return deserializar(msg)
                .chain(evento -> useCase.procesar(evento, msg.messageId()))
                .chain(() -> eliminar(msg.receiptHandle()))
                .onFailure().invoke(e ->
                        log.error("Error procesando mensaje {}: {}", msg.messageId(), e.getMessage()))
                .onFailure().recoverWithNull(); // no eliminar: SQS reintentará → DLQ
    }

    private Uni<EvaluacionCompletadaEvent> deserializar(Message msg) {
        try {
            return Uni.createFrom().item(
                    objectMapper.readValue(msg.body(), EvaluacionCompletadaEvent.class));
        } catch (Exception e) {
            return Uni.createFrom().failure(e);
        }
    }

    private Uni<Void> eliminar(String receiptHandle) {
        return Uni.createFrom().completionStage(() ->
                sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(receiptHandle)
                        .build()))
                .replaceWithVoid();
    }
}
```

## 8. `application.properties`

```properties
quarkus.http.port=8083

# ── DataSource — notifications_db ────────────────────────────
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=${NOTIF_DB_USERNAME:postgres}
quarkus.datasource.password=${NOTIF_DB_PASSWORD:postgres}
quarkus.datasource.jdbc.url=jdbc:postgresql://${NOTIF_DB_HOST:localhost}:5435/notifications_db
quarkus.hibernate-orm.database.generation=validate
# Migración aplicada manualmente antes de arrancar (ver sección 2)

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

## 9. Levantar en modo dev

```bash
cd backend/ms-notifications/infrastructure/entry-points/app
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

---

## Pruebas

### Dependencias — `ms-notifications/pom.xml` (raíz)

> `quarkus-junit5`, `mockito-core`, `mockito-junit-jupiter` y `assertj-core` ya están en el root POM generado por el scaffold.
> Solo agregar la dependencia específica para el test de integración:

```xml
<!-- DevServices PostgreSQL para integration tests -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-devservices-postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

### Pruebas Unitarias — `EmailSenderServiceTest.java`

Ubicación: `infrastructure/driven-adapters/ses/src/test/java/com/msnotifications/ses/adapter/`

```java
package com.msnotifications.ses.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ses.SesAsyncClient;
import software.amazon.awssdk.services.ses.model.*;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailSenderServiceTest {

    @Mock
    SesAsyncClient sesClient;

    @InjectMocks
    EmailSenderService emailSender;

    @Test
    void enviar_APROBADO_usa_asunto_con_APROBADA() {
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        SendEmailResponse.builder().messageId("ses-001").build()));

        emailSender.enviar("dest@email.com", "APROBADO",
                new BigDecimal("5000.00"), "2026-05-05T14:30:00Z")
                .await().indefinitely();

        var captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());

        var asunto = captor.getValue().message().subject().data();
        assertThat(asunto).containsIgnoringCase("APROBADA");
        assertThat(captor.getValue().destination().toAddresses())
                .containsExactly("dest@email.com");
    }

    @Test
    void enviar_RECHAZADO_usa_asunto_con_RECHAZADA() {
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        SendEmailResponse.builder().messageId("ses-002").build()));

        emailSender.enviar("dest@email.com", "RECHAZADO",
                new BigDecimal("3000.00"), "2026-05-05T14:30:00Z")
                .await().indefinitely();

        var captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());
        assertThat(captor.getValue().message().subject().data())
                .containsIgnoringCase("RECHAZADA");
    }

    @Test
    void enviar_llama_a_ses_exactamente_una_vez() {
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        SendEmailResponse.builder().messageId("ses-003").build()));

        emailSender.enviar("a@b.com", "APROBADO", BigDecimal.ONE, "2026-05-05T00:00:00Z")
                .await().indefinitely();

        verify(sesClient, times(1)).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void fallo_de_ses_propaga_excepcion() {
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        SesException.builder().message("SES error").build()));

        assertThatException()
                .isThrownBy(() -> emailSender.enviar(
                        "dest@email.com", "APROBADO",
                        BigDecimal.ONE, "2026-05-05T00:00:00Z").await().indefinitely());
    }

    @Test
    void cuerpo_html_APROBADO_contiene_monto() {
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        SendEmailResponse.builder().messageId("ses-004").build()));

        emailSender.enviar("dest@email.com", "APROBADO",
                new BigDecimal("7500.00"), "2026-05-05T14:30:00Z")
                .await().indefinitely();

        var captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());
        var html = captor.getValue().message().body().html().data();
        assertThat(html).contains("7,500.00");
    }
}
```

### Prueba de Integración — `ProcesarNotificacionUseCaseIT.java`

> `@QuarkusTest` con DevServices PostgreSQL. `EmailSenderService` se mockea con `@InjectMock`.
> Las aserciones de estado usan `NotificationPort` (el real, respaldado por el adapter) — sin
> importar `NotificationEntity` desde el módulo de tests del use case.

Ubicación: `application/use-cases/src/test/java/com/msnotifications/usecase/`

```java
package com.msnotifications.usecase;

import com.msnotifications.model.entity.EvaluacionCompletadaEvent;
import com.msnotifications.ses.adapter.EmailSenderService;
import com.msnotifications.usecase.port.NotificationPort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.TestReactiveTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@QuarkusTest
@RunOnVertxContext
class ProcesarNotificacionUseCaseIT {

    @Inject
    ProcesarNotificacionUseCase useCase;

    @Inject
    NotificationPort notificationPort;

    @InjectMock
    EmailSenderService emailSender;

    private EvaluacionCompletadaEvent evento(String evalId) {
        return new EvaluacionCompletadaEvent(
                evalId, "1713175071", "test@email.com", "Juan Test",
                "APROBADO", new BigDecimal("5000.00"), "USD", 3,
                "2026-05-05T14:30:00Z", "1.0");
    }

    // ── Happy path ────────────────────────────────────────────

    @Test
    @TestReactiveTransaction
    Uni<Void> procesar_persiste_notificacion_con_estado_ENVIADO() {
        var evalId = UUID.randomUUID().toString();
        when(emailSender.enviar(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        return useCase.procesar(evento(evalId), "sqs-msg-001")
                .chain(() -> notificationPort.existeEnviada(UUID.fromString(evalId)))
                .invoke(existe -> assertThat(existe).isTrue())
                .replaceWithVoid();
    }

    @Test
    @TestReactiveTransaction
    Uni<Void> procesar_invoca_emailSender_exactamente_una_vez() {
        when(emailSender.enviar(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        return useCase.procesar(evento(UUID.randomUUID().toString()), "sqs-msg-002")
                .invoke(() -> verify(emailSender, times(1))
                        .enviar(eq("test@email.com"), eq("APROBADO"), any(), any()));
    }

    // ── Idempotencia ──────────────────────────────────────────

    @Test
    @TestReactiveTransaction
    Uni<Void> segundo_procesamiento_del_mismo_evalId_no_genera_segundo_email() {
        var evalId = UUID.randomUUID().toString();
        when(emailSender.enviar(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        return useCase.procesar(evento(evalId), "sqs-msg-003")
                .chain(() -> useCase.procesar(evento(evalId), "sqs-msg-003b"))
                .invoke(() -> verify(emailSender, times(1)).enviar(any(), any(), any(), any()))
                .replaceWithVoid();
    }

    // ── Resiliencia ───────────────────────────────────────────

    @Test
    @TestReactiveTransaction
    Uni<Void> fallo_de_ses_propaga_error_al_caller() {
        when(emailSender.enviar(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("SES unavailable")));

        return useCase.procesar(evento(UUID.randomUUID().toString()), "sqs-msg-004")
                .onFailure().recoverWithItem((Void) null)
                .invoke(() -> verify(emailSender, times(1)).enviar(any(), any(), any(), any()))
                .replaceWithVoid();
    }
}
```

### Prueba de Integración — `NotificationConsumerIT.java`

> Valida que el consumer SQS orquesta correctamente: deserializa, delega al caso de uso,
> elimina el mensaje en caso de éxito y NO lo elimina en caso de fallo.

Ubicación: `infrastructure/entry-points/sqs-consumer/src/test/java/com/msnotifications/sqsconsumer/adapter/`

```java
package com.msnotifications.sqsconsumer.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msnotifications.usecase.ProcesarNotificacionUseCase;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
@RunOnVertxContext
class NotificationConsumerIT {

    @Inject
    NotificationConsumer consumer;

    @InjectMock
    SqsAsyncClient sqsClient;

    @InjectMock
    ProcesarNotificacionUseCase useCase;

    private final ObjectMapper mapper = new ObjectMapper();

    private Message mensajeValido(String evaluacionId) throws Exception {
        var cuerpo = mapper.writeValueAsString(Map.of(
                "evaluacionId",      evaluacionId,
                "cedula",            "1713175071",
                "destinatarioEmail", "test@email.com",
                "estadoFinal",       "APROBADO",
                "montoSolicitado",   new BigDecimal("5000.00"),
                "moneda",            "USD",
                "plazoAnios",        3,
                "fechaEvaluacion",   "2026-05-05T14:30:00Z",
                "version",           "1.0"
        ));
        return Message.builder()
                .messageId(UUID.randomUUID().toString())
                .receiptHandle("rh-" + evaluacionId)
                .body(cuerpo)
                .build();
    }

    @BeforeEach
    void setUp() {
        when(sqsClient.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        DeleteMessageResponse.builder().build()));
    }

    // ── Happy path ────────────────────────────────────────────

    @Test
    Uni<Void> procesar_mensaje_valido_delega_al_usecase() throws Exception {
        var msg = mensajeValido(UUID.randomUUID().toString());

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of(msg)).build()));
        when(useCase.procesar(any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        return consumer.procesarMensajes()
                .invoke(() -> verify(useCase, times(1)).procesar(any(), eq(msg.messageId())));
    }

    @Test
    Uni<Void> procesar_elimina_mensaje_de_sqs_tras_exito() throws Exception {
        var msg = mensajeValido(UUID.randomUUID().toString());

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of(msg)).build()));
        when(useCase.procesar(any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        return consumer.procesarMensajes()
                .invoke(() -> verify(sqsClient, times(1))
                        .deleteMessage(any(DeleteMessageRequest.class)));
    }

    // ── Resiliencia ───────────────────────────────────────────

    @Test
    Uni<Void> mensaje_malformado_no_elimina_mensaje_de_sqs() {
        var msgMalformado = Message.builder()
                .messageId("bad-msg")
                .receiptHandle("rh-bad")
                .body("{ esto no es json valido }")
                .build();

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder()
                                .messages(List.of(msgMalformado)).build()));

        return consumer.procesarMensajes()
                .invoke(() -> verify(sqsClient, never())
                        .deleteMessage(any(DeleteMessageRequest.class)));
    }

    @Test
    Uni<Void> fallo_del_usecase_no_elimina_mensaje_de_sqs() throws Exception {
        var msg = mensajeValido(UUID.randomUUID().toString());

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of(msg)).build()));
        when(useCase.procesar(any(), any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("use case failure")));

        return consumer.procesarMensajes()
                .invoke(() -> verify(sqsClient, never())
                        .deleteMessage(any(DeleteMessageRequest.class)));
    }

    @Test
    Uni<Void> cola_vacia_no_llama_al_usecase() {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of()).build()));

        return consumer.procesarMensajes()
                .invoke(() -> verifyNoInteractions(useCase));
    }
}
```

### Ejecutar

```bash
cd backend/ms-notifications

# Unitarios EmailSenderService (sin Docker)
mvn test -pl infrastructure/driven-adapters/ses

# Integración caso de uso (requiere Docker para DevServices PostgreSQL)
mvn test -pl application/use-cases

# Integración consumer SQS
mvn test -pl infrastructure/entry-points/sqs-consumer

# Todos
mvn test
```

---

## Estado esperado al finalizar
- [ ] `V1__create_notifications.sql` aplicado manualmente con psql antes de arrancar
- [ ] `@Scheduled(every="20s")` consumer activo y visible en logs
- [ ] Mensaje SQS de prueba procesado → estado `ENVIADO` en BD
- [ ] Email "enviado" vía LocalStack SES (log de confirmación)
- [ ] Mensaje duplicado ignorado (idempotencia por `evaluacion_id`)
- [ ] Mensaje malformado no procesado, permanece en cola para reintento
- [ ] `EmailSenderServiceTest` pasa: 5+ tests unitarios con SES mock
- [ ] `ProcesarNotificacionUseCaseIT` pasa: happy path, idempotencia, fallo SES
- [ ] `NotificationConsumerIT` pasa: delegación a use case, fallo use case, cola vacía
