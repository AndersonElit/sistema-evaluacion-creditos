# Paso 14 — Logging Estructurado en Cada Capa

## Objetivo
Implementar logs consistentes y con enmascaramiento de PII en todas las capas de los tres microservicios: `ms-credit-evaluation`, `ms-notifications` y `ms-risk`. El resultado es que cualquier evaluación de crédito pueda rastrearse de principio a fin solo con los logs.

> Lineamiento completo en `docs/12-logging.md`. Este paso contiene las instrucciones de implementación.

## Prerrequisitos
- Pasos 01–09b completados (stack corriendo)
- Los microservicios compilan y los tests pasan

---

## 1. Clase utilitaria de enmascaramiento

Crear en cada microservicio que maneje PII. Como los módulos no comparten código entre microservicios, se replica el utilitario en el módulo `use-cases` de cada servicio que lo necesite.

### 1.1 ms-credit-evaluation

**Archivo:** `backend/ms-credit-evaluation/application/use-cases/src/main/java/com/mscreditevaluation/usecases/util/LogMask.java`

```java
package com.mscreditevaluation.usecases.util;

import java.math.BigDecimal;

public final class LogMask {

    private LogMask() {}

    public static String cedula(String cedula) {
        if (cedula == null || cedula.length() != 10) return "[cédula-inválida]";
        return cedula.substring(0, 2) + "******" + cedula.substring(8);
    }

    public static String email(String email) {
        if (email == null || !email.contains("@")) return "[email-inválido]";
        int at = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(at);
    }

    public static String monto(BigDecimal monto) {
        if (monto == null) return "[null]";
        long miles = monto.longValue() / 1_000;
        return "[" + miles + "k-" + (miles + 10) + "k]";
    }
}
```

### 1.2 ms-notifications

**Archivo:** `backend/ms-notifications/application/use-cases/src/main/java/com/msnotifications/usecases/util/LogMask.java`

Mismo contenido, ajustando el `package` a `com.msnotifications.usecases.util`.

---

## 2. Configuración de logging en application.properties

### 2.1 ms-credit-evaluation

**Archivo:** `backend/ms-credit-evaluation/infrastructure/entry-points/app/src/main/resources/application.properties`

Agregar al final (sección estructural):

```properties
# ── Logging ───────────────────────────────────────────────────────────────────
quarkus.log.level=INFO
quarkus.log.category."com.mscreditevaluation".level=DEBUG
quarkus.log.category."io.quarkus".level=WARN
quarkus.log.category."org.hibernate".level=WARN
quarkus.log.category."software.amazon".level=WARN
quarkus.log.console.format=%d{HH:mm:ss} %-5p [%c{2.}] (%t) - %s%e%n
quarkus.log.console.color=true
quarkus.http.access-log.enabled=true
quarkus.http.access-log.pattern="%{REMOTE_HOST} %r %s %b %D ms"
```

### 2.2 ms-notifications

**Archivo:** `backend/ms-notifications/infrastructure/entry-points/app/src/main/resources/application.properties`

```properties
# ── Logging ───────────────────────────────────────────────────────────────────
quarkus.log.level=INFO
quarkus.log.category."com.msnotifications".level=DEBUG
quarkus.log.category."io.quarkus".level=WARN
quarkus.log.category."org.hibernate".level=WARN
quarkus.log.category."software.amazon".level=WARN
quarkus.log.console.format=%d{HH:mm:ss} %-5p [%c{2.}] (%t) - %s%e%n
quarkus.log.console.color=true
```

### 2.3 ms-risk

**Archivo:** `backend/ms-risk/infrastructure/entry-points/app/src/main/resources/application.properties`

```properties
# ── Logging ───────────────────────────────────────────────────────────────────
quarkus.log.level=INFO
quarkus.log.category."com.msrisk".level=DEBUG
quarkus.log.category."io.quarkus".level=WARN
quarkus.log.console.format=%d{HH:mm:ss} %-5p [%c{2.}] (%t) - %s%e%n
quarkus.log.console.color=true
quarkus.http.access-log.enabled=true
quarkus.http.access-log.pattern="%{REMOTE_HOST} %r %s %b %D ms"
```

---

## 3. ms-credit-evaluation — Entry Point (REST Resource)

**Archivo:** `backend/ms-credit-evaluation/infrastructure/entry-points/rest-api/src/main/java/com/mscreditevaluation/restapi/resource/CreditEvaluationResource.java`

### Agregar logger e imports

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mscreditevaluation.usecases.util.LogMask;

// En la clase:
private static final Logger log = LoggerFactory.getLogger(CreditEvaluationResource.class);
```

### Método POST /v1/credit-evaluations

```java
@POST
@RolesAllowed({"ADMIN", "ANALYST"})
public Uni<Response> evaluar(@Valid SolicitudCreditoRequest request) {
    long inicio = System.currentTimeMillis();
    String userId = jwt.getSubject();
    String cedulaMask = LogMask.cedula(request.cedula());

    log.info("→ POST /v1/credit-evaluations cedula={} monto={} evaluadorId={}",
            cedulaMask, LogMask.monto(request.montoSolicitado()), userId);

    var command = toCommand(request, userId);
    return useCase.ejecutar(command)
            .map(result -> {
                log.info("← 201 CREATED evaluacionId={} estado={} elapsed={}ms",
                        result.id(), result.estado(), System.currentTimeMillis() - inicio);
                return Response.created(toUri(result.id())).entity(toResponse(result)).build();
            });
}
```

### Método GET /v1/credit-evaluations

```java
@GET
@Authenticated
public Uni<Response> listar(@QueryParam("cedula") String cedula,
                             @QueryParam("page") @DefaultValue("0") int page) {
    log.debug("→ GET /v1/credit-evaluations cedula={} page={}",
            cedula != null ? LogMask.cedula(cedula) : "[sin-filtro]", page);

    return useCase.listar(cedula, page)
            .map(results -> {
                log.debug("← 200 OK resultados={}", results.size());
                return Response.ok(results).build();
            });
}
```

### GlobalExceptionMapper — eventos de seguridad

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GlobalExceptionMapper implements ExceptionMapper<Exception> {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionMapper.class);

    @Override
    public Response toResponse(Exception e) {
        if (e instanceof NotAuthorizedException) {
            log.warn("JWT inválido o ausente — 401 error={}", e.getMessage());
            return Response.status(401).entity(errorBody("No autenticado")).build();
        }
        if (e instanceof ForbiddenException) {
            log.warn("Acceso no autorizado — 403 error={}", e.getMessage());
            return Response.status(403).entity(errorBody("Acceso denegado")).build();
        }
        if (e instanceof ConstraintViolationException cve) {
            String campos = cve.getConstraintViolations().stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining(", "));
            log.warn("Validación fallida — 400 campos={}", campos);
            return Response.status(400).entity(errorBody(campos)).build();
        }
        log.error("Error no manejado — 500 error={}", e.getMessage(), e);
        return Response.status(500).entity(errorBody("Error interno")).build();
    }
}
```

---

## 4. ms-credit-evaluation — Use Case

**Archivo:** `backend/ms-credit-evaluation/application/use-cases/src/main/java/com/mscreditevaluation/usecases/EvaluarCreditoUseCase.java`

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mscreditevaluation.usecases.util.LogMask;

private static final Logger log = LoggerFactory.getLogger(EvaluarCreditoUseCase.class);

public Uni<EvaluacionCreditoResult> ejecutar(SolicitudCreditoCommand cmd) {
    String cedulaMask = LogMask.cedula(cmd.cedula());

    log.debug("Iniciando evaluación cedula={} monto={} plazo={}m evaluadorId={}",
            cedulaMask, LogMask.monto(cmd.montoSolicitado()), cmd.plazoMeses(), cmd.evaluadorId());

    return riskService.consultarRiesgo(cmd.cedula())
            .invoke(risk -> log.debug("Datos de riesgo obtenidos cedula={} score={} deudas={}",
                    cedulaMask, risk.score(), LogMask.monto(risk.totalDeudas())))
            .flatMap(risk -> {
                EstadoEvaluacion estado = EvaluacionCredito.evaluar(
                        risk.score(), risk.totalDeudas(), cmd.salario(),
                        cmd.montoSolicitado(), cmd.plazoMeses());

                log.info("Decisión de crédito cedula={} estado={} score={} evaluadorId={}",
                        cedulaMask, estado, risk.score(), cmd.evaluadorId());

                EvaluacionCredito evaluacion = EvaluacionCredito.builder()
                        .id(UUID.randomUUID())
                        .cedula(new Cedula(cmd.cedula()))
                        /* ... demás campos ... */
                        .build();

                return repository.guardar(evaluacion)
                        .invoke(e -> log.debug("Evaluación persistida evaluacionId={} cedula={}",
                                e.id(), cedulaMask))
                        .flatMap(e -> notificationPort.publicarEvaluacionCompletada(e)
                                .onFailure().recoverWithItem(ex -> {
                                    log.warn("Publicación SQS fallida — evaluación completada sin notificación evaluacionId={} error={}",
                                            e.id(), ex.getMessage());
                                    return null;
                                })
                                .map(ignored -> toResult(e)));
            })
            .onFailure().invoke(e ->
                    log.error("Error en evaluación crédito cedula={} error={}",
                            cedulaMask, e.getMessage(), e));
}
```

---

## 5. ms-credit-evaluation — Driven Adapters

### 5.1 CreditEvaluationRepositoryAdapter (PostgreSQL)

**Archivo:** `backend/ms-credit-evaluation/infrastructure/driven-adapters/postgres/...`

```java
private static final Logger log = LoggerFactory.getLogger(CreditEvaluationRepositoryAdapter.class);

@Override
public Uni<EvaluacionCredito> guardar(EvaluacionCredito evaluacion) {
    String cedulaMask = LogMask.cedula(evaluacion.getCedula().valor());
    log.debug("Persistiendo evaluación evaluacionId={} cedula={} estado={}",
            evaluacion.getId(), cedulaMask, evaluacion.getEstadoFinal());

    CreditEvaluationEntity entity = mapper.toEntity(evaluacion);
    return entity.persist()
            .onFailure().invoke(e -> log.error(
                    "Error persistiendo evaluación evaluacionId={} cedula={} error={}",
                    evaluacion.getId(), cedulaMask, e.getMessage(), e))
            .map(v -> evaluacion);
}

@Override
public Uni<List<EvaluacionCredito>> listar(String cedula, int page) {
    String cedulaMask = cedula != null ? LogMask.cedula(cedula) : "[sin-filtro]";
    log.debug("Consultando evaluaciones cedula={} pagina={}", cedulaMask, page);

    return CreditEvaluationEntity.findByCedula(cedula, page)
            .invoke(lista -> log.debug("Resultados obtenidos cedula={} cantidad={}", cedulaMask, lista.size()));
}
```

### 5.2 RiskServiceClientAdapter

**Archivo:** `backend/ms-credit-evaluation/infrastructure/driven-adapters/risk-service-client/...`

```java
private static final Logger log = LoggerFactory.getLogger(RiskServiceClientAdapter.class);

@Override
public Uni<RiskData> consultarRiesgo(String cedula) {
    String cedulaMask = LogMask.cedula(cedula);
    long inicio = System.currentTimeMillis();

    log.debug("Consultando ms-risk cedula={}", cedulaMask);

    return Uni.combine().all()
            .unis(riskClient.obtenerScore(cedula), riskClient.obtenerDeudas(cedula))
            .with((score, deudas) -> {
                long elapsed = System.currentTimeMillis() - inicio;

                if (elapsed > 1_500) {
                    log.warn("Respuesta lenta de ms-risk elapsed={}ms cedula={}", elapsed, cedulaMask);
                } else {
                    log.debug("Respuesta ms-risk recibida cedula={} score={} elapsed={}ms",
                            cedulaMask, score.score(), elapsed);
                }

                return new RiskData(score.score(), deudas.totalDeudas());
            })
            .onFailure().invoke(e ->
                    log.error("Error consultando ms-risk cedula={} elapsed={}ms error={}",
                            cedulaMask, System.currentTimeMillis() - inicio, e.getMessage(), e));
}
```

### 5.3 SqsNotificationPublisher

**Archivo:** `backend/ms-credit-evaluation/infrastructure/driven-adapters/sqs-producer/...`

```java
private static final Logger log = LoggerFactory.getLogger(SqsNotificationPublisher.class);

@Override
public Uni<Void> publicarEvaluacionCompletada(EvaluacionCompletadaEvent event) {
    log.debug("Publicando evento SQS evaluacionId={}", event.evaluacionId());

    return Uni.createFrom().completionStage(() -> sqsClient.sendMessage(buildRequest(event)))
            .invoke(r -> log.info("Evento publicado en SQS evaluacionId={} messageId={}",
                    event.evaluacionId(), r.messageId()))
            .onFailure().invoke(e -> log.error("Error publicando en SQS evaluacionId={} error={}",
                    event.evaluacionId(), e.getMessage(), e))
            .replaceWithVoid();
}
```

---

## 6. ms-notifications — Entry Point (SQS Consumer)

**Archivo:** `backend/ms-notifications/infrastructure/entry-points/sqs-consumer/...NotificationConsumer.java`

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

@Scheduled(every = "20s")
public void poll() {
    log.debug("Polling SQS queue={} maxMessages=10", queueUrl);

    List<Message> messages = sqsClient.receiveMessages(buildRequest()).messages();

    if (messages.isEmpty()) {
        log.debug("Sin mensajes en cola");
        return;
    }

    log.debug("Mensajes recibidos cantidad={}", messages.size());

    for (Message msg : messages) {
        processMessage(msg);
    }
}

private void processMessage(Message msg) {
    String evaluacionId = "[desconocido]";
    try {
        EvaluacionCompletadaEvent event = deserialize(msg.body());
        evaluacionId = event.evaluacionId().toString();

        log.info("Mensaje SQS recibido messageId={} evaluacionId={}", msg.messageId(), evaluacionId);

        useCase.ejecutar(event).await().indefinitely();

        sqsClient.deleteMessage(buildDelete(msg));
        log.debug("Mensaje eliminado de SQS messageId={}", msg.messageId());

    } catch (Exception e) {
        log.error("Error procesando mensaje SQS messageId={} evaluacionId={} error={}",
                msg.messageId(), evaluacionId, e.getMessage(), e);
    }
}
```

---

## 7. ms-notifications — Use Case

**Archivo:** `backend/ms-notifications/application/use-cases/...ProcesarNotificacionUseCase.java`

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.msnotifications.usecases.util.LogMask;

private static final Logger log = LoggerFactory.getLogger(ProcesarNotificacionUseCase.class);

public Uni<Void> ejecutar(EvaluacionCompletadaEvent event) {
    log.debug("Iniciando procesamiento notificación evaluacionId={} tipo={}",
            event.evaluacionId(), event.estado());

    return notificationRepository.existePorEvaluacion(event.evaluacionId())
            .flatMap(existe -> {
                if (existe) {
                    log.warn("Notificación duplicada ignorada — idempotencia activada evaluacionId={}",
                            event.evaluacionId());
                    return Uni.createFrom().voidItem();
                }

                return emailSender.enviar(event)
                        .flatMap(v -> notificationRepository.guardar(toEntity(event)))
                        .invoke(n -> log.info(
                                "Notificación procesada evaluacionId={} destinatario={} tipo={}",
                                event.evaluacionId(), LogMask.email(event.email()), event.estado()))
                        .replaceWithVoid();
            })
            .onFailure().invoke(e ->
                    log.error("Error procesando notificación evaluacionId={} error={}",
                            event.evaluacionId(), e.getMessage(), e));
}
```

---

## 8. ms-notifications — Driven Adapters

### 8.1 EmailSenderService (SES)

```java
private static final Logger log = LoggerFactory.getLogger(EmailSenderService.class);

public Uni<Void> enviar(EvaluacionCompletadaEvent event) {
    log.debug("Enviando email via SES evaluacionId={} destinatario={} tipo={}",
            event.evaluacionId(), LogMask.email(event.email()), event.estado());

    return Uni.createFrom().completionStage(() -> sesClient.sendEmail(buildRequest(event)))
            .invoke(r -> log.info("Email enviado via SES evaluacionId={} destinatario={} tipo={}",
                    event.evaluacionId(), LogMask.email(event.email()), event.estado()))
            .onFailure().invoke(e ->
                    log.error("Error enviando email SES evaluacionId={} destinatario={} error={}",
                            event.evaluacionId(), LogMask.email(event.email()), e.getMessage(), e))
            .replaceWithVoid();
}
```

### 8.2 NotificationRepositoryAdapter (PostgreSQL)

```java
private static final Logger log = LoggerFactory.getLogger(NotificationRepositoryAdapter.class);

public Uni<Boolean> existePorEvaluacion(UUID evaluacionId) {
    log.debug("Verificando idempotencia evaluacionId={}", evaluacionId);
    return NotificationEntity.count("evaluacionId", evaluacionId)
            .map(count -> count > 0);
}

public Uni<Notification> guardar(Notification notification) {
    log.debug("Persistiendo notificación evaluacionId={}", notification.evaluacionId());
    NotificationEntity entity = mapper.toEntity(notification);
    return entity.persist()
            .onFailure().invoke(e -> log.error(
                    "Error persistiendo notificación evaluacionId={} error={}",
                    notification.evaluacionId(), e.getMessage(), e))
            .map(v -> notification);
}
```

---

## 9. ms-risk — Entry Point (REST Resource)

**Archivo:** `backend/ms-risk/infrastructure/entry-points/rest-api/.../RiskResource.java`

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger log = LoggerFactory.getLogger(RiskResource.class);

@GET
@Path("/score/{cedula}")
public Uni<Response> obtenerScore(@PathParam("cedula") String cedula) {
    String cedulaMask = cedula.length() == 10
            ? cedula.substring(0, 2) + "******" + cedula.substring(8)
            : "[cédula-inválida]";

    log.debug("→ GET /v1/risk/score/{} (latencia simulada 2s)", cedulaMask);

    return Uni.createFrom().item(() -> generateScore(cedula))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .invoke(r -> log.debug("← score={} cedula={}", r.score(), cedulaMask))
            .map(r -> Response.ok(r).build());
}

@GET
@Path("/debts/{cedula}")
public Uni<Response> obtenerDeudas(@PathParam("cedula") String cedula) {
    String cedulaMask = cedula.length() == 10
            ? cedula.substring(0, 2) + "******" + cedula.substring(8)
            : "[cédula-inválida]";

    log.debug("→ GET /v1/risk/debts/{} (latencia simulada 1.5s)", cedulaMask);

    return Uni.createFrom().item(() -> generateDebts(cedula))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .invoke(r -> log.debug("← deudas={} cedula={}", r.totalDeudas(), cedulaMask))
            .map(r -> Response.ok(r).build());
}
```

---

## 10. Verificación

### 10.1 Arrancar el stack y ejecutar una evaluación

```bash
# Levantar infraestructura
docker compose up -d

# En otra terminal: arrancar ms-credit-evaluation en dev mode
cd backend/ms-credit-evaluation/infrastructure/entry-points/app
mvn quarkus:dev
```

### 10.2 Llamada de prueba

```bash
# Obtener token de Keycloak (sustituir credenciales según paso 02)
TOKEN=$(curl -s -X POST http://localhost:9000/realms/banco/protocol/openid-connect/token \
  -d "grant_type=password&client_id=ms-credit-evaluation&username=analista1&password=password123" \
  | jq -r '.access_token')

# Enviar solicitud de evaluación
curl -s -X POST http://localhost:8080/v1/credit-evaluations \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "cedula": "1714761372",
    "montoSolicitado": 15000,
    "plazoMeses": 24,
    "salario": 2500
  }' | jq .
```

### 10.3 Salida de logs esperada

```
10:42:30 INFO  [C.C.CreditEvaluationResource] (vert.x-eventloop-thread-0) - → POST /v1/credit-evaluations cedula=17****72 monto=[10k-20k] evaluadorId=abc-123
10:42:30 DEBUG [C.E.EvaluarCreditoUseCase] (vert.x-eventloop-thread-0) - Iniciando evaluación cedula=17****72 monto=[10k-20k] plazo=24m evaluadorId=abc-123
10:42:30 DEBUG [C.R.RiskServiceClientAdapter] (vert.x-eventloop-thread-0) - Consultando ms-risk cedula=17****72
10:42:32 DEBUG [C.R.RiskServiceClientAdapter] (vert.x-eventloop-thread-0) - Respuesta ms-risk recibida cedula=17****72 score=720 elapsed=2012ms
10:42:32 INFO  [C.E.EvaluarCreditoUseCase] (vert.x-eventloop-thread-0) - Decisión de crédito cedula=17****72 estado=APROBADO score=720 evaluadorId=abc-123
10:42:32 DEBUG [C.C.CreditEvaluationRepositoryAdapter] (vert.x-eventloop-thread-0) - Persistiendo evaluación evaluacionId=f4a1... cedula=17****72 estado=APROBADO
10:42:32 INFO  [C.S.SqsNotificationPublisher] (vert.x-eventloop-thread-0) - Evento publicado en SQS evaluacionId=f4a1... messageId=msg-001
10:42:32 INFO  [C.C.CreditEvaluationResource] (vert.x-eventloop-thread-0) - ← 201 CREATED evaluacionId=f4a1... estado=APROBADO elapsed=2050ms
```

En ms-notifications (después de ~20s):
```
10:42:50 DEBUG [C.N.NotificationConsumer] (scheduler-thread-1) - Polling SQS queue=http://localstack:4566/... maxMessages=10
10:42:50 INFO  [C.N.NotificationConsumer] (scheduler-thread-1) - Mensaje SQS recibido messageId=msg-001 evaluacionId=f4a1...
10:42:50 DEBUG [C.P.ProcesarNotificacionUseCase] (scheduler-thread-1) - Iniciando procesamiento notificación evaluacionId=f4a1... tipo=APROBADO
10:42:50 INFO  [C.E.EmailSenderService] (scheduler-thread-1) - Email enviado via SES evaluacionId=f4a1... destinatario=j***@gmail.com tipo=APROBADO
10:42:50 INFO  [C.P.ProcesarNotificacionUseCase] (scheduler-thread-1) - Notificación procesada evaluacionId=f4a1... destinatario=j***@gmail.com tipo=APROBADO
```

### 10.4 Verificar enmascaramiento

```bash
# La cédula NUNCA debe aparecer en claro en los logs
docker logs ms-credit-evaluation 2>&1 | grep "1714761372"
# Resultado esperado: sin coincidencias

# La versión enmascarada sí debe aparecer
docker logs ms-credit-evaluation 2>&1 | grep "17\*\*\*\*72"
# Resultado esperado: múltiples líneas de log
```

---

## 11. Checklist de Completitud

- [ ] `LogMask` creado en ms-credit-evaluation y ms-notifications
- [ ] `application.properties` actualizado en los tres microservicios
- [ ] Logger declarado en todas las clases de las capas especificadas
- [ ] `CreditEvaluationResource`: logs de request/response con enmascaramiento
- [ ] `EvaluarCreditoUseCase`: logs de inicio, decisión y error
- [ ] `CreditEvaluationRepositoryAdapter`: logs de operaciones de BD
- [ ] `RiskServiceClientAdapter`: logs de llamada y respuesta a ms-risk
- [ ] `SqsNotificationPublisher`: logs de publicación SQS
- [ ] `GlobalExceptionMapper`: logs de errores 401/403/500
- [ ] `NotificationConsumer`: logs de poll, recepción y procesamiento
- [ ] `ProcesarNotificacionUseCase`: logs de idempotencia y completitud
- [ ] `EmailSenderService`: logs de envío SES
- [ ] `RiskResource`: logs de solicitudes recibidas
- [ ] Verificado que la cédula no aparece en claro en ningún log
- [ ] Verificado el flujo completo con los logs esperados
