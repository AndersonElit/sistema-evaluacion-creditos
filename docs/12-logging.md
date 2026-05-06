# Lineamiento de Logging

> Todo código de producción en los microservicios de backend debe emitir logs estructurados en cada capa del flujo. El objetivo es que cualquier transacción pueda trazarse de principio a fin sin necesidad de adjuntar un debugger.

---

## 1. Motivación

| Problema sin logging estructurado | Impacto |
|-----------------------------------|---------|
| No se sabe en qué capa falló una evaluación | Debugging lento, escalaciones innecesarias |
| Sin correlación de eventos entre microservicios | Imposible reconstruir el flujo de una transacción |
| PII (cédula, email, salario) en logs en texto plano | Riesgo de cumplimiento y auditoría |
| Sin logs de seguridad | No se detectan accesos no autorizados ni ataques |
| Niveles de log sin criterio | Producción inundada de DEBUG o carente de INFO útil |

---

## 2. Framework de Logging

**Quarkus** usa JBoss Logging como backend nativo. Todos los microservicios emplean **SLF4J** como API, con JBoss Logging como proveedor — no es necesario agregar dependencias adicionales.

### Declaración del logger

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MiClase {
    private static final Logger log = LoggerFactory.getLogger(MiClase.class);
}
```

**Regla:** usar siempre `LoggerFactory.getLogger(MiClase.class)` con la clase concreta. No usar `Logger.getLogger(...)` de JBoss directamente para mantener consistencia.

---

## 3. Niveles de Log y Cuándo Usarlos

| Nivel | Cuándo usarlo | Ejemplos en este sistema |
|-------|---------------|--------------------------|
| `ERROR` | Fallo que impide completar la operación y requiere atención humana | Error al conectar a BD, timeout total en ms-risk, fallo de SES sin posibilidad de reintento |
| `WARN` | Condición inesperada de la que el sistema se recuperó o degradación controlada | Circuit breaker abierto, duplicado ignorado por idempotencia, reintento de SQS |
| `INFO` | Hitos del flujo de negocio: entrada, decisión, salida | Evaluación iniciada, evaluación APROBADA/RECHAZADA, email enviado |
| `DEBUG` | Detalle técnico útil en desarrollo o troubleshooting | Datos del risk service, query ejecutada, parámetros de llamada HTTP |
| `TRACE` | Detalle interno de muy bajo nivel (no usar en producción) | Iteraciones de algoritmo, serialización/deserialización paso a paso |

**Nivel por entorno:**

```
Producción → INFO  (captura hitos de negocio y errores)
Desarrollo → DEBUG (captura todo el detalle técnico)
```

---

## 4. Reglas de PII — Qué Enmascarar

Los datos personales **nunca** se loguean en texto plano. Usar siempre las funciones de enmascaramiento.

| Campo | Regla de enmascaramiento | Ejemplo |
|-------|--------------------------|---------|
| Cédula | Mostrar los 2 primeros (provincia) y los 2 últimos dígitos | `17` + `******` + `23` → `17******23` |
| Email | Mostrar primer carácter del localpart y dominio completo | `juan.perez@gmail.com` → `j***@gmail.com` |
| Monto/Salario | Mostrar solo el orden de magnitud (rango de miles) | `15.750,00` → `[10k-20k]` |
| Contraseñas / Tokens | Nunca loguear, ni siquiera enmascarados | Omitir el campo en su totalidad |
| Número de cuenta | Mostrar solo los últimos 4 dígitos | `**********1234` |

### Utilidad de enmascaramiento

```java
// Clase utilitaria en un módulo compartido (o inline donde se necesite)
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
        long miles = monto.longValue() / 1000;
        return "[" + miles + "k-" + (miles + 10) + "k]";
    }
}
```

---

## 5. Qué Loguear por Capa

### 5.1 Entry Points — REST Resource

Los recursos REST son el punto de entrada del flujo. Deben loguear:

| Momento | Nivel | Qué incluir |
|---------|-------|-------------|
| Solicitud recibida | `INFO` | método HTTP, path, `userId` del JWT, `cedula` enmascarada, `monto` |
| Respuesta enviada | `INFO` | HTTP status, `evaluacionId`, `estado`, tiempo transcurrido (`elapsed`) |
| Error de validación (`@Valid`) | `WARN` | campo inválido, mensaje de validación (sin el valor si es PII) |
| Error no manejado | `ERROR` | clase de excepción, mensaje (sin stack trace completo en INFO, con stack en ERROR) |

```java
// Ejemplo: CreditEvaluationResource
@POST
public Uni<Response> evaluar(@Valid SolicitudCreditoRequest request) {
    long inicio = System.currentTimeMillis();
    String cedulaMask = LogMask.cedula(request.cedula());
    String userId = jwt.getSubject();

    log.info("→ POST /v1/credit-evaluations cedula={} monto={} evaluadorId={}",
            cedulaMask, LogMask.monto(request.montoSolicitado()), userId);

    return useCase.ejecutar(toCommand(request, userId))
            .map(result -> {
                log.info("← 201 evaluacionId={} estado={} elapsed={}ms",
                        result.id(), result.estado(), System.currentTimeMillis() - inicio);
                return Response.created(location(result.id())).entity(toResponse(result)).build();
            });
}
```

### 5.2 Entry Points — SQS Consumer (ms-notifications)

| Momento | Nivel | Qué incluir |
|---------|-------|-------------|
| Inicio de poll | `DEBUG` | URL de cola, `maxMessages` |
| Mensaje recibido | `INFO` | `messageId`, `evaluacionId` |
| Duplicado ignorado | `WARN` | `evaluacionId` (idempotencia activada) |
| Procesamiento completado | `INFO` | `evaluacionId`, email enmascarado, `elapsed` |
| Fallo en mensaje | `ERROR` | `messageId`, `evaluacionId`, clase de excepción, mensaje de error |

```java
// Ejemplo: NotificationConsumer
log.debug("Polling SQS queue={} maxMessages=10", queueUrl);

log.info("Mensaje SQS recibido messageId={} evaluacionId={}", msg.messageId(), event.evaluacionId());

log.warn("Notificación duplicada ignorada — idempotencia activada evaluacionId={}", event.evaluacionId());

log.info("Notificación procesada evaluacionId={} destinatario={} elapsed={}ms",
        event.evaluacionId(), LogMask.email(event.email()), elapsed);

log.error("Error procesando mensaje SQS messageId={} evaluacionId={} error={}",
        msg.messageId(), event.evaluacionId(), e.getMessage(), e);
```

### 5.3 Use Cases

Los casos de uso orquestan el flujo de negocio. Deben loguear las **decisiones de negocio** (no detalles de infraestructura).

| Momento | Nivel | Qué incluir |
|---------|-------|-------------|
| Inicio de ejecución | `DEBUG` | `cedula` enmascarada, `monto`, `plazo`, `evaluadorId` |
| Datos de riesgo recibidos | `DEBUG` | `cedula` enmascarada, `score`, total de deudas (enmascarado) |
| Decisión tomada | `INFO` | `evaluacionId`, `cedula` enmascarada, `estado`, motivo del rechazo si aplica |
| Fallback de circuit breaker | `WARN` | `cedula` enmascarada, servicio degradado |
| Error de negocio | `WARN` | tipo de excepción, `cedula` enmascarada |
| Error técnico | `ERROR` | `cedula` enmascarada, clase de excepción, mensaje |

```java
// Ejemplo: EvaluarCreditoUseCase
public Uni<EvaluacionCreditoResult> ejecutar(SolicitudCreditoCommand cmd) {
    String cedulaMask = LogMask.cedula(cmd.cedula());

    log.debug("Iniciando evaluación cedula={} monto={} plazo={} evaluadorId={}",
            cedulaMask, LogMask.monto(cmd.montoSolicitado()), cmd.plazoMeses(), cmd.evaluadorId());

    return riskService.consultarRiesgo(cmd.cedula())
            .invoke(risk -> log.debug("Datos de riesgo obtenidos cedula={} score={} deudas={}",
                    cedulaMask, risk.score(), LogMask.monto(risk.totalDeudas())))
            .flatMap(risk -> {
                EstadoEvaluacion estado = EvaluacionCredito.evaluar(...);

                log.info("Decisión de crédito cedula={} estado={} score={} evaluadorId={}",
                        cedulaMask, estado, risk.score(), cmd.evaluadorId());

                return repository.guardar(evaluacion)
                        .invoke(e -> log.debug("Evaluación persistida evaluacionId={}", e.id()));
            })
            .onFailure().invoke(e ->
                    log.error("Error en evaluación crédito cedula={} error={}",
                            cedulaMask, e.getMessage(), e));
}
```

### 5.4 Driven Adapters — Base de Datos (Panache)

| Momento | Nivel | Qué incluir |
|---------|-------|-------------|
| Persistencia de entidad | `DEBUG` | `evaluacionId`, `cedula` enmascarada, `estado` |
| Consulta de entidades | `DEBUG` | criterios de búsqueda enmascarados, número de resultados |
| Error de BD | `ERROR` | operación, `evaluacionId` (si aplica), clase de excepción, mensaje |

```java
// Ejemplo: CreditEvaluationRepositoryAdapter
log.debug("Persistiendo evaluación evaluacionId={} cedula={} estado={}",
        entity.getId(), LogMask.cedula(entity.getCedula()), entity.getEstado());

log.error("Error accediendo a base de datos operacion=guardar evaluacionId={} error={}",
        entity.getId(), e.getMessage(), e);
```

### 5.5 Driven Adapters — REST Client (ms-risk)

| Momento | Nivel | Qué incluir |
|---------|-------|-------------|
| Inicio de llamada | `DEBUG` | servicio destino, `cedula` enmascarada |
| Respuesta recibida | `DEBUG` | `cedula` enmascarada, resultado resumido, `elapsed` |
| Respuesta lenta (> 1.5s) | `WARN` | `elapsed`, `cedula` enmascarada |
| Timeout o error de red | `ERROR` | `cedula` enmascarada, clase de excepción, `elapsed` |

```java
log.debug("Consultando ms-risk cedula={}", LogMask.cedula(cedula));

log.debug("Respuesta ms-risk recibida cedula={} score={} elapsed={}ms",
        LogMask.cedula(cedula), score, elapsed);

log.warn("Respuesta lenta de ms-risk elapsed={}ms cedula={}", elapsed, LogMask.cedula(cedula));

log.error("Error consultando ms-risk cedula={} elapsed={}ms error={}",
        LogMask.cedula(cedula), elapsed, e.getMessage(), e);
```

### 5.6 Driven Adapters — SQS Producer

| Momento | Nivel | Qué incluir |
|---------|-------|-------------|
| Evento publicado | `INFO` | `evaluacionId`, `messageId` retornado por SQS |
| Fallo recuperado (recoverWithNull) | `WARN` | `evaluacionId`, mensaje de error |
| Error crítico | `ERROR` | `evaluacionId`, clase de excepción |

```java
log.info("Evento publicado en SQS evaluacionId={} messageId={}",
        event.evaluacionId(), response.messageId());

log.warn("No se pudo publicar evento SQS — evaluación continuará sin notificación evaluacionId={} error={}",
        event.evaluacionId(), e.getMessage());
```

### 5.7 Driven Adapters — SES Email Sender

| Momento | Nivel | Qué incluir |
|---------|-------|-------------|
| Email enviado | `INFO` | `evaluacionId`, email enmascarado, tipo (`APROBADO`/`RECHAZADO`) |
| Error de SES | `ERROR` | `evaluacionId`, email enmascarado, clase de excepción |

```java
log.info("Email enviado via SES evaluacionId={} destinatario={} tipo={}",
        notificacion.evaluacionId(), LogMask.email(notificacion.email()), notificacion.tipo());

log.error("Error enviando email SES evaluacionId={} destinatario={} error={}",
        notificacion.evaluacionId(), LogMask.email(notificacion.email()), e.getMessage(), e);
```

---

## 6. Eventos de Seguridad

Los errores de autenticación y autorización deben loguearse siempre en `WARN` o `ERROR` en el `GlobalExceptionMapper` o en un filtro JAX-RS.

| Evento | Nivel | Qué incluir |
|--------|-------|-------------|
| JWT inválido o expirado (401) | `WARN` | IP de origen (`@Context HttpServletRequest`), path, mensaje del error JWT |
| Acceso no autorizado (403) | `WARN` | `userId` del JWT, rol actual, path, rol requerido |
| Excepción no manejada (500) | `ERROR` | clase de excepción, mensaje (con stack trace) |

```java
// GlobalExceptionMapper
@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionMapper.class);

    public Response toResponse(Exception e) {
        if (e instanceof UnauthorizedException) {
            log.warn("JWT inválido — acceso denegado path={} error={}", requestPath, e.getMessage());
            return Response.status(401)...;
        }
        if (e instanceof ForbiddenException) {
            log.warn("Acceso no autorizado userId={} path={}", userId, requestPath);
            return Response.status(403)...;
        }
        log.error("Error no manejado path={} error={}", requestPath, e.getMessage(), e);
        return Response.status(500)...;
    }
}
```

---

## 7. Formato de Logs

### 7.1 Formato en Desarrollo (consola legible)

```
HH:mm:ss LEVEL [clase-abreviada] (thread) - mensaje
```

Ejemplo:
```
10:42:31 INFO  [C.EvaluarCreditoUseCase] (vert.x-eventloop-thread-0) - Decisión de crédito cedula=17******23 estado=APROBADO score=720 evaluadorId=abc123
10:42:31 INFO  [C.SqsNotificationPublisher] (vert.x-eventloop-thread-0) - Evento publicado en SQS evaluacionId=f4a1... messageId=mq-001...
```

### 7.2 Formato en Producción (JSON para ingestión por ELK/CloudWatch)

En producción se activa el formato JSON via variable de entorno o SSM, sin cambios de código:

```properties
# Activar via SSM en producción: quarkus.log.console.json=true
```

Salida JSON:
```json
{
  "timestamp": "2026-05-06T10:42:31.123Z",
  "level": "INFO",
  "loggerName": "com.mscreditevaluation.usecases.EvaluarCreditoUseCase",
  "message": "Decisión de crédito cedula=17******23 estado=APROBADO score=720 evaluadorId=abc123",
  "threadName": "vert.x-eventloop-thread-0",
  "serviceName": "ms-credit-evaluation"
}
```

---

## 8. Configuración en application.properties

Agregar en cada microservicio (solo la sección estructural — los niveles por paquete pueden moverse a SSM en producción):

```properties
# ── Logging ───────────────────────────────────────────────────────────────────
# Nivel global: INFO en producción, DEBUG en desarrollo (sobrescribible via env var)
quarkus.log.level=INFO
quarkus.log.category."com.mscreditevaluation".level=DEBUG
quarkus.log.category."com.msnotifications".level=DEBUG
quarkus.log.category."com.msrisk".level=DEBUG

# Reducir ruido de frameworks internos
quarkus.log.category."io.quarkus".level=WARN
quarkus.log.category."org.hibernate".level=WARN
quarkus.log.category."software.amazon".level=WARN

# Formato consola para desarrollo (desactivar JSON aquí, activar en prod via SSM)
quarkus.log.console.format=%d{HH:mm:ss} %-5p [%c{2.}] (%t) - %s%e%n
quarkus.log.console.color=true

# Access log HTTP (registra todas las peticiones entrantes)
quarkus.http.access-log.enabled=true
quarkus.http.access-log.pattern="%{REMOTE_HOST} %r %s %b %D ms"
```

Para producción, añadir via SSM:
```
/banco/ms-<servicio>/quarkus.log.level=INFO
/banco/ms-<servicio>/quarkus.log.console.json=true
```

---

## 9. Lo Que Nunca Se Debe Loguear

| Dato | Razón |
|------|-------|
| Contraseñas / secretos | Riesgo crítico de seguridad |
| Tokens JWT completos | Un token logueado es una credencial robada |
| Cédula sin enmascarar | Dato personal protegido por normativa |
| Email completo | Dato personal protegido |
| Salario o monto exacto | Dato sensible, mostrar solo rango |
| Stack traces completos en INFO/WARN | Ruido + posible filtración de paths internos |
| Queries SQL con valores interpolados | Usar solo `?` (PreparedStatement) |

---

## 10. Logging en Código Reactivo (Mutiny)

En pipelines reactivos (`Uni.flatMap`, `.map`, etc.) el logging se hace con los operadores `.invoke()` y `.onFailure().invoke()` para no romper la cadena reactiva:

```java
return riskService.consultarRiesgo(cedula)
    .invoke(r -> log.debug("Score obtenido cedula={} score={}", cedulaMask, r.score()))
    .flatMap(r -> repository.guardar(evaluar(r)))
    .invoke(e -> log.info("Evaluación guardada evaluacionId={}", e.id()))
    .onFailure().invoke(ex -> log.error("Fallo en pipeline cedula={} error={}", cedulaMask, ex.getMessage(), ex));
```

**Regla:** No usar `log.*` dentro de lambdas de `.map()` que transforman datos — usar `.invoke()` para efectos secundarios (logs, métricas) sin alterar el valor del `Uni`.

---

## 11. Resumen por Microservicio

| Microservicio | Capas con logging | Eventos clave |
|---------------|-------------------|---------------|
| ms-credit-evaluation | REST Resource, EvaluarCreditoUseCase, CreditEvaluationRepositoryAdapter, RiskServiceClient, SqsNotificationPublisher, GlobalExceptionMapper | Solicitud recibida, decisión APROBADO/RECHAZADO, persistencia, publicación SQS, errores de auth |
| ms-notifications | NotificationConsumer, ProcesarNotificacionUseCase, EmailSenderService, NotificationRepositoryAdapter | Poll SQS, mensaje recibido, duplicado ignorado, email enviado |
| ms-risk | RiskResource | Solicitud recibida, score/deudas retornados (latencia simulada) |

> Ver guía de implementación paso a paso en `development-plan/14-logging.md`.
> Ver decisión arquitectónica en `docs/09-adr.md` (ADR-012).
