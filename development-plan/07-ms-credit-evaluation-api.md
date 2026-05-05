# Paso 07 — ms-credit-evaluation: REST API, Seguridad y Configuración Final

## Objetivo
Implementar el entry point REST de `ms-credit-evaluation`: resource con `@RolesAllowed`,
validación de entrada con Bean Validation, `ExceptionMapper` global, y la configuración
completa de `application.properties` (JWT/OIDC, CORS, Swagger).

## Prerrequisitos
- Paso 02 completado (Keycloak con realm banco y usuarios)
- Paso 06 completado (caso de uso funcional)

## 1. Dependencias — `infrastructure/entry-points/rest-api/pom.xml`

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-resteasy-reactive-jackson</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-hibernate-validator</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-jwt</artifactId>
</dependency>
```

## 2. DTOs de Request/Response — `infrastructure/entry-points/rest-api`

### `SolicitudCreditoRequest.java`
```java
package com.mscreditevaluation.restapi;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record SolicitudCreditoRequest(

    @NotBlank(message = "La cédula es requerida")
    @Pattern(regexp = "\\d{10}", message = "La cédula debe tener exactamente 10 dígitos numéricos")
    String cedula,

    @NotNull(message = "El monto solicitado es requerido")
    @DecimalMin(value = "0.01", message = "El monto debe ser positivo")
    BigDecimal montoSolicitado,

    @Min(value = 1, message = "El plazo mínimo es 1 año")
    @Max(value = 30, message = "El plazo máximo es 30 años")
    int plazoAnios,

    @NotNull(message = "El salario es requerido")
    @DecimalMin(value = "0.01", message = "El salario debe ser positivo")
    BigDecimal salario,

    @Email(message = "Email del solicitante inválido")
    String destinatarioEmail
) {}
```

### `EvaluacionCreditoResponse.java`
```java
package com.mscreditevaluation.restapi;

import com.mscreditevaluation.model.EvaluacionCredito;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record EvaluacionCreditoResponse(
    UUID id,
    String cedula,
    BigDecimal montoSolicitado,
    int plazoAnios,
    BigDecimal salario,
    int scoreRiesgo,
    BigDecimal deudaMensualTotal,
    String estadoFinal,
    Instant fechaEvaluacion,
    UUID evaluadoPorId
) {
    public static EvaluacionCreditoResponse from(EvaluacionCredito e) {
        return new EvaluacionCreditoResponse(
            e.getId(),
            e.getCedula().valor(),
            e.getMontoSolicitado().cantidad(),
            e.getPlazoAnios(),
            e.getSalario().cantidad(),
            e.getScoreRiesgo().valor(),
            e.getDeudaMensual().cantidad(),
            e.getEstadoFinal().name(),
            e.getFechaEvaluacion(),
            e.getEvaluadoPorId()
        );
    }
}
```

## 3. Resource REST — `infrastructure/entry-points/rest-api`

### `CreditEvaluationResource.java`
```java
package com.mscreditevaluation.restapi;

import com.mscreditevaluation.usecases.EvaluarCreditoUseCase;
import com.mscreditevaluation.usecases.SolicitudCreditoCommand;
import io.quarkus.security.Authenticated;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.net.URI;
import java.util.UUID;

@Path("/v1/credit-evaluations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CreditEvaluationResource {

    @Inject EvaluarCreditoUseCase useCase;
    @Inject JsonWebToken jwt;

    @POST
    @RolesAllowed({"ADMIN", "ANALYST"})
    public Uni<Response> evaluar(@Valid SolicitudCreditoRequest request) {
        UUID evaluadorId = UUID.fromString(jwt.getSubject());
        String email = jwt.getClaim("email");

        var command = new SolicitudCreditoCommand(
            request.cedula(),
            request.montoSolicitado(),
            request.plazoAnios(),
            request.salario(),
            evaluadorId,
            request.destinatarioEmail() != null ? request.destinatarioEmail() : email
        );

        return useCase.ejecutar(command)
                .map(result -> {
                    var resp = EvaluacionCreditoResponse.from(result.evaluacion());
                    return Response.created(
                            URI.create("/v1/credit-evaluations/" + resp.id()))
                            .entity(resp)
                            .build();
                });
    }

    @GET
    @Authenticated
    public Uni<Response> listar(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return Uni.createFrom()
                .item(() -> useCase.listarTodas(page, size))
                .map(lista -> lista.stream()
                        .map(r -> EvaluacionCreditoResponse.from(r.evaluacion()))
                        .toList())
                .map(lista -> Response.ok(lista).build());
    }

    @GET
    @Path("/{id}")
    @Authenticated
    public Uni<Response> buscarPorId(@PathParam("id") UUID id) {
        return useCase.buscarPorId(id)
                .map(result -> Response.ok(
                        EvaluacionCreditoResponse.from(result.evaluacion())).build());
    }
}
```

## 4. ExceptionMapper Global — `infrastructure/entry-points/rest-api`

### `GlobalExceptionMapper.java`
```java
package com.mscreditevaluation.restapi;

import com.mscreditevaluation.model.Cedula;
import com.mscreditevaluation.postgres.RiskServiceUnavailableException;
import com.mscreditevaluation.usecases.EvaluacionNotFoundException;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.stream.Collectors;

@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger log = Logger.getLogger(GlobalExceptionMapper.class);

    @Override
    public Response toResponse(Exception ex) {
        if (ex instanceof ConstraintViolationException cve) {
            var detalles = cve.getConstraintViolations().stream()
                    .map(v -> new ErrorDetail(v.getPropertyPath().toString(), v.getMessage()))
                    .collect(Collectors.toList());
            return Response.status(422).entity(new ErrorResponse(422, "Validation Error", detalles)).build();
        }
        if (ex instanceof IllegalArgumentException iae) {
            return Response.status(422).entity(new ErrorResponse(422, iae.getMessage(), null)).build();
        }
        if (ex instanceof EvaluacionNotFoundException) {
            return Response.status(404).entity(new ErrorResponse(404, ex.getMessage(), null)).build();
        }
        if (ex instanceof RiskServiceUnavailableException) {
            return Response.status(503).entity(
                    new ErrorResponse(503, "El servicio de riesgos no está disponible. Intente más tarde.", null)).build();
        }
        // Error genérico — no exponer detalles internos
        log.errorf(ex, "Error no manejado: %s", ex.getMessage());
        return Response.status(500).entity(new ErrorResponse(500, "Error interno", null)).build();
    }

    record ErrorDetail(String campo, String mensaje) {}
    record ErrorResponse(int status, String error, Object detalles) {
        ErrorResponse { }
        public Instant timestamp() { return Instant.now(); }
    }
}
```

## 5. `application.properties` final

```properties
quarkus.http.port=8080

# ── DataSource ────────────────────────────────────────────────
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=${DB_USERNAME:postgres}
quarkus.datasource.password=${DB_PASSWORD:postgres}
quarkus.datasource.jdbc.url=jdbc:postgresql://${DB_HOST:localhost}:5432/creditos_db
quarkus.hibernate-orm.database.generation=validate
quarkus.flyway.migrate-at-start=true
quarkus.flyway.locations=classpath:db/migration

# ── JWT/OIDC — Keycloak ──────────────────────────────────────
mp.jwt.verify.publickey.location=http://localhost:9000/realms/banco/protocol/openid-connect/certs
mp.jwt.verify.issuer=http://localhost:9000/realms/banco
quarkus.smallrye-jwt.role-paths=groups

# ── CORS ─────────────────────────────────────────────────────
quarkus.http.cors=true
quarkus.http.cors.origins=http://localhost:3000
quarkus.http.cors.methods=GET,POST,OPTIONS
quarkus.http.cors.headers=Content-Type,Authorization

# ── REST Client ms-risk ──────────────────────────────────────
quarkus.rest-client.risk-service.url=http://localhost:8081

# ── AWS SQS (LocalStack en dev) ──────────────────────────────
quarkus.sqs.aws.region=${AWS_REGION:us-east-1}
quarkus.sqs.endpoint-override=${SQS_ENDPOINT_URL:http://localhost:4566}
sqs.queue.url=${SQS_QUEUE_URL:http://localhost:4566/000000000000/credit-evaluation-notifications}
aws.accessKeyId=${AWS_ACCESS_KEY_ID:test}
aws.secretAccessKey=${AWS_SECRET_ACCESS_KEY:test}

# ── OpenAPI / Swagger ────────────────────────────────────────
mp.openapi.extensions.smallrye.info.version=1.0.0
quarkus.swagger-ui.always-include=true
quarkus.swagger-ui.path=/swagger-ui

# ── Health ──────────────────────────────────────────────────
quarkus.smallrye-health.root-path=/q/health
```

## Verificación

```bash
# Obtener JWT del analista
TOKEN=$(curl -s -X POST \
  "http://localhost:9000/realms/banco/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=credit-evaluation-spa" \
  -d "username=analyst@banco.com&password=Analyst123!" \
  | jq -r '.access_token')

# POST crear evaluación (ANALYST) → 201
curl -s -X POST http://localhost:8080/v1/credit-evaluations \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cedula":"1713175071","montoSolicitado":5000.00,"plazoAnios":3,"salario":2000.00}' \
  | jq .

# GET listar → 200
curl -s http://localhost:8080/v1/credit-evaluations \
  -H "Authorization: Bearer $TOKEN" | jq .

# Sin token → 401
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/v1/credit-evaluations
# 401

# VIEWER intenta POST → 403
VIEWER_TOKEN=$(curl -s -X POST \
  "http://localhost:9000/realms/banco/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=credit-evaluation-spa" \
  -d "username=viewer@banco.com&password=Viewer123!" \
  | jq -r '.access_token')
curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8080/v1/credit-evaluations \
  -H "Authorization: Bearer $VIEWER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cedula":"1713175071","montoSolicitado":5000.00,"plazoAnios":3,"salario":2000.00}'
# 403

# Cédula inválida → 422
curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8080/v1/credit-evaluations \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cedula":"1234567890","montoSolicitado":5000.00,"plazoAnios":3,"salario":2000.00}'
# 422

# Swagger UI
# http://localhost:8080/swagger-ui
```

## Estado esperado al finalizar
- [ ] `POST /v1/credit-evaluations` requiere rol ADMIN o ANALYST → 201 con evaluación
- [ ] `GET /v1/credit-evaluations` requiere cualquier rol autenticado → 200
- [ ] Sin token → 401, VIEWER en POST → 403, cédula inválida → 422, riesgos caído → 503
- [ ] ExceptionMapper no expone stack traces ni clases Java internas
- [ ] CORS configurado para `http://localhost:3000`
- [ ] Swagger UI accesible en http://localhost:8080/swagger-ui
