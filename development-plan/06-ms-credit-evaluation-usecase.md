# Paso 06 — ms-credit-evaluation: Caso de Uso, REST Client y SQS Publisher

## Objetivo
Implementar el caso de uso de evaluación de crédito completo:
cliente REST para ms-risk con llamadas paralelas usando Mutiny,
publicador SQS para `EvaluacionCompletada`, y el servicio de aplicación
que orquesta todo aplicando la regla de negocio.

## Prerrequisitos
- Paso 03 completado (ms-risk corriendo en puerto 8081)
- Paso 04 completado (dominio definido)
- Paso 05 completado (BD y repositorio funcionales)
- Paso 09 (LocalStack) puede completarse antes de probar SQS, pero el servicio arranca sin él

## 1. Dependencias adicionales — `infrastructure/entry-points/app/pom.xml`

> `quarkus-smallrye-jwt` ya lo genera el scaffold en el módulo `app`. Solo agregar:

```xml
<!-- REST Client para ms-risk -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-rest-client-reactive-jackson</artifactId>
</dependency>
<!-- Fault Tolerance (Circuit Breaker, Timeout) -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-fault-tolerance</artifactId>
</dependency>
```

## 2. DTOs del caso de uso — `application/use-cases`

### `SolicitudCreditoCommand.java`
```java
package com.mscreditevaluation.usecases.command;

import java.math.BigDecimal;
import java.util.UUID;

public record SolicitudCreditoCommand(
    String cedula,
    BigDecimal montoSolicitado,
    int plazoAnios,
    BigDecimal salario,
    UUID evaluadoPorId,
    String destinatarioEmail
) {}
```

### `EvaluacionCreditoResult.java`
```java
package com.mscreditevaluation.usecases.result;

import com.mscreditevaluation.model.entity.EvaluacionCredito;

public record EvaluacionCreditoResult(EvaluacionCredito evaluacion) {}
```

## 3. Caso de Uso — `application/use-cases`

### `EvaluarCreditoUseCase.java`
```java
package com.mscreditevaluation.usecases;

import com.mscreditevaluation.model.entity.EstadoEvaluacion;
import com.mscreditevaluation.model.entity.EvaluacionCredito;
import com.mscreditevaluation.model.port.EvaluacionCreditoRepository;
import com.mscreditevaluation.model.port.NotificationPort;
import com.mscreditevaluation.model.port.RiskServicePort;
import com.mscreditevaluation.model.valueobject.Cedula;
import com.mscreditevaluation.model.valueobject.Dinero;
import com.mscreditevaluation.model.valueobject.ScoreRiesgo;
import com.mscreditevaluation.usecases.command.SolicitudCreditoCommand;
import com.mscreditevaluation.usecases.exception.EvaluacionNotFoundException;
import com.mscreditevaluation.usecases.result.EvaluacionCreditoResult;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

public class EvaluarCreditoUseCase {

    private final EvaluacionCreditoRepository repository;
    private final RiskServicePort riskService;
    private final NotificationPort notificationPort;

    public EvaluarCreditoUseCase(EvaluacionCreditoRepository repository,
                                  RiskServicePort riskService,
                                  NotificationPort notificationPort) {
        this.repository = repository;
        this.riskService = riskService;
        this.notificationPort = notificationPort;
    }

    public Uni<EvaluacionCreditoResult> ejecutar(SolicitudCreditoCommand cmd) {
        Cedula cedula = new Cedula(cmd.cedula());  // lanza excepción si inválida

        return Uni.createFrom()
                .item(() -> riskService.consultarRiesgo(cmd.cedula()))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map(riskData -> {
                    var score = new ScoreRiesgo(riskData.score());
                    var deuda = Dinero.usd(riskData.totalDeudaMensual());
                    var monto = Dinero.usd(cmd.montoSolicitado());
                    var salario = Dinero.usd(cmd.salario());

                    EstadoEvaluacion estado = EvaluacionCredito.evaluar(
                            score, deuda, salario, monto, cmd.plazoAnios());

                    EvaluacionCredito evaluacion = EvaluacionCredito.builder()
                            .cedula(cedula)
                            .montoSolicitado(monto)
                            .plazoAnios(cmd.plazoAnios())
                            .salario(salario)
                            .scoreRiesgo(score)
                            .deudaMensual(deuda)
                            .estadoFinal(estado)
                            .evaluadoPorId(cmd.evaluadoPorId())
                            .build();

                    EvaluacionCredito persistida = repository.guardar(evaluacion);

                    // Fire-and-forget: no bloquea la respuesta al cliente
                    notificationPort.publicarEvaluacionCompletada(
                            persistida, cmd.destinatarioEmail());

                    return new EvaluacionCreditoResult(persistida);
                });
    }

    public Uni<EvaluacionCreditoResult> buscarPorId(java.util.UUID id) {
        return Uni.createFrom().item(() ->
                repository.buscarPorId(id)
                        .map(EvaluacionCreditoResult::new)
                        .orElseThrow(() -> new EvaluacionNotFoundException(id))
        ).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
```

### `EvaluacionNotFoundException.java`
```java
package com.mscreditevaluation.usecases.exception;

import java.util.UUID;

public class EvaluacionNotFoundException extends RuntimeException {
    public EvaluacionNotFoundException(UUID id) {
        super("Evaluación no encontrada: " + id);
    }
}
```

## 4. REST Client para ms-risk — `infrastructure/driven-adapters/postgres` (o nuevo módulo)

### `RiskServiceClient.java` (interface MicroProfile)
```java
package com.mscreditevaluation.postgres.repository;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RegisterRestClient(configKey = "risk-service")
@Path("/v1/risk")
@Produces(MediaType.APPLICATION_JSON)
public interface RiskServiceClient {

    record ScoreResponse(String cedula, int score, Instant timestamp) {}
    record DebtDto(String id, String descripcion, BigDecimal mensualidad) {}
    record DeudasResponse(String cedula, List<DebtDto> deudas,
                          BigDecimal totalMensual, Instant timestamp) {}

    @GET
    @Path("/score/{cedula}")
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10000)
    ScoreResponse getScore(@PathParam("cedula") String cedula);

    @GET
    @Path("/debts/{cedula}")
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10000)
    DeudasResponse getDebts(@PathParam("cedula") String cedula);
}
```

### `RiskServiceAdapter.java` — Implementa `RiskServicePort` con llamadas paralelas
```java
package com.mscreditevaluation.postgres.repository;

import com.mscreditevaluation.model.port.RiskServicePort;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class RiskServiceAdapter implements RiskServicePort {

    @RestClient
    RiskServiceClient riskClient;

    @Override
    public RiskData consultarRiesgo(String cedula) {
        // Llamadas paralelas: score (~2s) + deudas (~1.5s) = ~2s total (no 3.5s)
        CompletableFuture<RiskServiceClient.ScoreResponse> scoreFuture =
                CompletableFuture.supplyAsync(() -> riskClient.getScore(cedula),
                        Infrastructure.getDefaultWorkerPool());

        CompletableFuture<RiskServiceClient.DeudasResponse> debtsFuture =
                CompletableFuture.supplyAsync(() -> riskClient.getDebts(cedula),
                        Infrastructure.getDefaultWorkerPool());

        try {
            CompletableFuture.allOf(scoreFuture, debtsFuture).join();
            int score = scoreFuture.get().score();
            BigDecimal deudaTotal = debtsFuture.get().totalMensual() != null
                    ? debtsFuture.get().totalMensual()
                    : BigDecimal.ZERO;
            return new RiskData(score, deudaTotal);
        } catch (Exception e) {
            throw new RiskServiceUnavailableException("No se pudo consultar el servicio de riesgos", e);
        }
    }
}
```

### `RiskServiceUnavailableException.java`
```java
package com.mscreditevaluation.postgres.repository;

public class RiskServiceUnavailableException extends RuntimeException {
    public RiskServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

## 5. SQS Publisher — `infrastructure/driven-adapters/sqs-producer`

### `SqsNotificationPublisher.java`
```java
package com.mscreditevaluation.sqsproducer.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mscreditevaluation.model.entity.EvaluacionCredito;
import com.mscreditevaluation.model.port.NotificationPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Instant;
import java.util.Map;

@ApplicationScoped
public class SqsNotificationPublisher implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(SqsNotificationPublisher.class);

    @Inject SqsClient sqsClient;
    @Inject ObjectMapper objectMapper;

    @ConfigProperty(name = "sqs.queue.url")
    String queueUrl;

    @Override
    public void publicarEvaluacionCompletada(EvaluacionCredito evaluacion,
                                              String destinatarioEmail) {
        try {
            Map<String, Object> evento = Map.of(
                "evaluacionId",     evaluacion.getId().toString(),
                "cedula",           evaluacion.getCedula().valor(),
                "destinatarioEmail", destinatarioEmail,
                "estadoFinal",      evaluacion.getEstadoFinal().name(),
                "montoSolicitado",  evaluacion.getMontoSolicitado().cantidad(),
                "moneda",           "USD",
                "plazoAnios",       evaluacion.getPlazoAnios(),
                "fechaEvaluacion",  evaluacion.getFechaEvaluacion().toString(),
                "version",          "1.0"
            );

            String body = objectMapper.writeValueAsString(evento);

            var response = sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());

            log.info("Evento publicado en SQS: evaluacionId={}, messageId={}",
                    evaluacion.getId(), response.messageId());

        } catch (Exception e) {
            // No falla la evaluación si SQS no está disponible
            log.error("Error publicando en SQS para evaluacion {}: {}",
                    evaluacion.getId(), e.getMessage());
        }
    }
}
```

## 6. `application.properties` — agregar REST Client y SQS

```properties
# ── REST Client ms-risk ──────────────────────────────────────
quarkus.rest-client.risk-service.url=http://localhost:8081

# ── AWS SQS ──────────────────────────────────────────────────
quarkus.sqs.aws.region=${AWS_REGION:us-east-1}
quarkus.sqs.endpoint-override=${SQS_ENDPOINT_URL:http://localhost:4566}
sqs.queue.url=${SQS_QUEUE_URL:http://localhost:4566/000000000000/credit-evaluation-notifications}

aws.accessKeyId=${AWS_ACCESS_KEY_ID:test}
aws.secretAccessKey=${AWS_SECRET_ACCESS_KEY:test}
```

## Verificación

```bash
# Con ms-risk corriendo (paso 03) y BD lista (paso 05):
cd ms-credit-evaluation/infrastructure/entry-points/app
mvn quarkus:dev

# Test básico del caso de uso vía REST (aún sin JWT — se añade en paso 07)
curl -s -X POST http://localhost:8080/v1/credit-evaluations \
  -H "Content-Type: application/json" \
  -d '{
    "cedula": "1713175071",
    "montoSolicitado": 5000.00,
    "plazoAnios": 3,
    "salario": 2000.00
  }' | jq .
# Debe retornar evaluación con estadoFinal APROBADO o RECHAZADO y persistirse en BD
```

---

## Pruebas Unitarias del Caso de Uso

> Sin infraestructura real: todos los puertos se mockean con Mockito.

### Dependencias — `application/use-cases/pom.xml`

> `junit-jupiter`, `mockito-core`, `mockito-junit-jupiter` y `assertj-core` ya están en el root POM generado por el scaffold.
> `mutiny` está en scope compile del módulo `application/use-cases` — el scope compile cubre los tests, no es necesario re-declararlo.
>
> No se requieren dependencias adicionales para este módulo.

### `EvaluarCreditoUseCaseTest.java`

Ubicación: `application/use-cases/src/test/java/com/mscreditevaluation/usecases/`

```java
package com.mscreditevaluation.usecases;

import com.mscreditevaluation.model.entity.EstadoEvaluacion;
import com.mscreditevaluation.model.entity.EvaluacionCredito;
import com.mscreditevaluation.model.port.EvaluacionCreditoRepository;
import com.mscreditevaluation.model.port.NotificationPort;
import com.mscreditevaluation.model.port.RiskServicePort;
import com.mscreditevaluation.usecases.command.SolicitudCreditoCommand;
import com.mscreditevaluation.usecases.result.EvaluacionCreditoResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvaluarCreditoUseCaseTest {

    @Mock EvaluacionCreditoRepository repository;
    @Mock RiskServicePort             riskService;
    @Mock NotificationPort            notificationPort;

    EvaluarCreditoUseCase useCase;

    private static final UUID EVALUADOR = UUID.randomUUID();

    private static final SolicitudCreditoCommand CMD = new SolicitudCreditoCommand(
            "1713175071", new BigDecimal("5000.00"), 3,
            new BigDecimal("2000.00"), EVALUADOR, "solicitante@email.com");

    @BeforeEach
    void setUp() {
        useCase = new EvaluarCreditoUseCase(repository, riskService, notificationPort);
        // repositorio retorna lo que recibe (simula persist)
        when(repository.guardar(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Happy path ────────────────────────────────────────────

    @Test
    void ejecutar_aprobado_con_score_alto() {
        when(riskService.consultarRiesgo("1713175071"))
                .thenReturn(new RiskServicePort.RiskData(85, new BigDecimal("200.00")));

        var result = useCase.ejecutar(CMD).await().indefinitely();

        assertThat(result.evaluacion().getEstadoFinal()).isEqualTo(EstadoEvaluacion.APROBADO);
        assertThat(result.evaluacion().getCedula().valor()).isEqualTo("1713175071");
    }

    @Test
    void ejecutar_rechazado_con_score_70() {
        when(riskService.consultarRiesgo(anyString()))
                .thenReturn(new RiskServicePort.RiskData(70, new BigDecimal("50.00")));

        var result = useCase.ejecutar(CMD).await().indefinitely();

        assertThat(result.evaluacion().getEstadoFinal()).isEqualTo(EstadoEvaluacion.RECHAZADO);
    }

    // ── Orquestación ──────────────────────────────────────────

    @Test
    void ejecutar_llama_a_repositorio_exactamente_una_vez() {
        when(riskService.consultarRiesgo(anyString()))
                .thenReturn(new RiskServicePort.RiskData(80, BigDecimal.ONE));

        useCase.ejecutar(CMD).await().indefinitely();

        verify(repository, times(1)).guardar(any(EvaluacionCredito.class));
    }

    @Test
    void ejecutar_publica_notificacion_con_email_del_comando() {
        when(riskService.consultarRiesgo(anyString()))
                .thenReturn(new RiskServicePort.RiskData(80, BigDecimal.ONE));

        useCase.ejecutar(CMD).await().indefinitely();

        verify(notificationPort, times(1))
                .publicarEvaluacionCompletada(any(), eq("solicitante@email.com"));
    }

    @Test
    void ejecutar_guarda_evaluadoPorId_del_comando() {
        when(riskService.consultarRiesgo(anyString()))
                .thenReturn(new RiskServicePort.RiskData(80, BigDecimal.ONE));

        useCase.ejecutar(CMD).await().indefinitely();

        var captor = ArgumentCaptor.forClass(EvaluacionCredito.class);
        verify(repository).guardar(captor.capture());
        assertThat(captor.getValue().getEvaluadoPorId()).isEqualTo(EVALUADOR);
    }

    // ── Cédula inválida ───────────────────────────────────────

    @Test
    void ejecutar_lanza_excepcion_si_cedula_es_invalida_sin_llamar_a_risk() {
        var cmdInvalido = new SolicitudCreditoCommand(
                "1234567890", new BigDecimal("5000"), 3,
                new BigDecimal("2000"), EVALUADOR, "e@e.com");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> useCase.ejecutar(cmdInvalido).await().indefinitely());

        verifyNoInteractions(riskService, repository, notificationPort);
    }

    // ── Risk service no disponible ────────────────────────────

    @Test
    void ejecutar_no_persiste_cuando_risk_service_falla() {
        when(riskService.consultarRiesgo(anyString()))
                .thenThrow(new RuntimeException("ms-risk timeout"));

        assertThatException()
                .isThrownBy(() -> useCase.ejecutar(CMD).await().indefinitely());

        verify(repository, never()).guardar(any());
        verify(notificationPort, never()).publicarEvaluacionCompletada(any(), any());
    }

    // ── SQS falla silenciosamente ─────────────────────────────

    @Test
    void fallo_en_notificacion_no_impide_respuesta_al_cliente() {
        when(riskService.consultarRiesgo(anyString()))
                .thenReturn(new RiskServicePort.RiskData(85, new BigDecimal("100")));
        doThrow(new RuntimeException("SQS no disponible"))
                .when(notificationPort).publicarEvaluacionCompletada(any(), any());

        // La evaluación debe completarse aunque SQS falle
        assertThatNoException()
                .isThrownBy(() -> useCase.ejecutar(CMD).await().indefinitely());

        verify(repository, times(1)).guardar(any());
    }

    // ── Score y datos de riesgo se propagan al agregado ───────

    @Test
    void ejecutar_almacena_score_y_deuda_retornados_por_risk_service() {
        when(riskService.consultarRiesgo(anyString()))
                .thenReturn(new RiskServicePort.RiskData(92, new BigDecimal("350.00")));

        var result = useCase.ejecutar(CMD).await().indefinitely();

        assertThat(result.evaluacion().getScoreRiesgo().valor()).isEqualTo(92);
        assertThat(result.evaluacion().getDeudaMensual().cantidad())
                .isEqualByComparingTo("350.00");
    }
}
```

### Ejecutar

```bash
cd ms-credit-evaluation
mvn test -pl application/use-cases
# Resultado esperado: BUILD SUCCESS — 8+ tests en verde
```

---

## Estado esperado al finalizar
- [ ] `EvaluarCreditoUseCase` orquesta risk + regla + persistencia + SQS
- [ ] Llamadas a ms-risk son paralelas (latencia ~2s, no 3.5s)
- [ ] Circuit Breaker activo en las llamadas a ms-risk
- [ ] `SqsNotificationPublisher` publica mensaje en LocalStack (no falla si SQS no está)
- [ ] Evaluación se persiste en `creditos_db`
- [ ] `EvaluarCreditoUseCaseTest` pasa: 8+ tests incluyendo cédula inválida, risk caído, SQS silencioso
