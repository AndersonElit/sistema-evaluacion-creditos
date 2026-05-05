# Paso 03 — ms-risk: Servicio de Riesgos Mock

## Objetivo
Generar el scaffold de `ms-risk` e implementar sus dos endpoints REST:
`GET /v1/risk/score/{cedula}` (score aleatorio 0–100, latencia ~2s) y
`GET /v1/risk/debts/{cedula}` (deudas aleatorias, latencia ~1.5s).
No requiere autenticación ni base de datos — es un servicio interno mock.

## Prerrequisitos
- Paso 01 completado (infraestructura Docker levantada)
- jbang instalado (`jbang --version`)
- Java 21+, Maven

## 1. Generar Scaffold

```bash
jbang scaffold/MavenHexagonalScaffold.java -n ms-risk
```

Resultado: carpeta `ms-risk/` con estructura Maven multimódulo hexagonal.

## 2. Dominio — `domain/model`

### `RiskProfile.java`
```java
package com.msrisk.model.entity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RiskProfile(String cedula, int score, List<Debt> debts) {

    public BigDecimal totalMonthlyDebt() {
        return debts.stream()
                .map(Debt::monthlyPayment)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
```

### `Debt.java`
```java
package com.msrisk.model.entity;

import java.math.BigDecimal;
import java.util.UUID;

public record Debt(UUID id, String description, BigDecimal monthlyPayment) {}
```

## 3. Puerto de salida — `domain/model`

### `RiskPort.java`
```java
package com.msrisk.model.port;

import com.msrisk.model.entity.RiskProfile;

public interface RiskPort {
    int getScore(String cedula);
    RiskProfile getProfile(String cedula);
}
```

## 4. Caso de Uso — `application/use-cases`

### `GetRiskProfileUseCase.java`
```java
package com.msrisk.usecases;

import com.msrisk.model.entity.RiskProfile;
import com.msrisk.model.port.RiskPort;

public class GetRiskProfileUseCase {

    private final RiskPort riskPort;

    public GetRiskProfileUseCase(RiskPort riskPort) {
        this.riskPort = riskPort;
    }

    public int getScore(String cedula) {
        return riskPort.getScore(cedula);
    }

    public RiskProfile getProfile(String cedula) {
        return riskPort.getProfile(cedula);
    }
}
```

## 5. Adaptador Mock — `infrastructure/driven-adapters/postgres` (renombrar a mock)

> Reemplazar el módulo postgres por un adaptador mock. Renombrar el directorio a `mock-risk` o simplemente
> implementar directamente en el `driven-adapters/postgres` de forma temporal.

### `MockRiskAdapter.java`
```java
package com.msrisk.postgres.repository;

import com.msrisk.model.entity.Debt;
import com.msrisk.model.entity.RiskProfile;
import com.msrisk.model.port.RiskPort;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class MockRiskAdapter implements RiskPort {

    private static final Random RNG = new Random();

    @Override
    public int getScore(String cedula) {
        simulateLatency(2000);
        return ThreadLocalRandom.current().nextInt(0, 101);
    }

    @Override
    public RiskProfile getProfile(String cedula) {
        simulateLatency(1500);
        int debtCount = ThreadLocalRandom.current().nextInt(0, 6);
        List<Debt> debts = new ArrayList<>();
        for (int i = 0; i < debtCount; i++) {
            BigDecimal monthly = BigDecimal.valueOf(
                    ThreadLocalRandom.current().nextDouble(50, 500));
            debts.add(new Debt(UUID.randomUUID(), "Deuda " + (i + 1), monthly));
        }
        int score = ThreadLocalRandom.current().nextInt(0, 101);
        return new RiskProfile(cedula, score, debts);
    }

    private void simulateLatency(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

## 6. DTOs de Respuesta — `infrastructure/entry-points/rest-api`

### `ScoreResponse.java`
```java
package com.msrisk.restapi.dto;

import java.time.Instant;

public record ScoreResponse(String cedula, int score, Instant timestamp) {}
```

### `DebtDto.java`
```java
package com.msrisk.restapi.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DebtDto(UUID id, String descripcion, BigDecimal mensualidad) {}
```

### `DeudasResponse.java`
```java
package com.msrisk.restapi.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DeudasResponse(String cedula, List<DebtDto> deudas,
                              BigDecimal totalMensual, Instant timestamp) {}
```

## 7. Resource REST — `infrastructure/entry-points/rest-api`

### `RiskResource.java`
```java
package com.msrisk.restapi.resource;

import com.msrisk.model.port.RiskPort;
import com.msrisk.restapi.dto.DebtDto;
import com.msrisk.restapi.dto.DeudasResponse;
import com.msrisk.restapi.dto.ScoreResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;

@Path("/v1/risk")
@Produces(MediaType.APPLICATION_JSON)
public class RiskResource {

    @Inject
    RiskPort riskPort;

    @GET
    @Path("/score/{cedula}")
    public Response getScore(@PathParam("cedula") String cedula) {
        if (!cedula.matches("\\d{10}")) {
            return Response.status(400)
                    .entity("{\"error\":\"Formato de cédula inválido\"}")
                    .build();
        }
        int score = riskPort.getScore(cedula);
        return Response.ok(new ScoreResponse(cedula, score, Instant.now())).build();
    }

    @GET
    @Path("/debts/{cedula}")
    public Response getDebts(@PathParam("cedula") String cedula) {
        if (!cedula.matches("\\d{10}")) {
            return Response.status(400)
                    .entity("{\"error\":\"Formato de cédula inválido\"}")
                    .build();
        }
        var profile = riskPort.getProfile(cedula);
        var dtos = profile.debts().stream()
                .map(d -> new DebtDto(d.id(), d.description(), d.monthlyPayment()))
                .toList();
        return Response.ok(new DeudasResponse(
                cedula, dtos, profile.totalMonthlyDebt(), Instant.now())).build();
    }
}
```

## 8. Wiring — `infrastructure/entry-points/app`

### `BeanConfig.java`
```java
package com.msrisk;

import com.msrisk.model.port.RiskPort;
import com.msrisk.postgres.repository.MockRiskAdapter;
import com.msrisk.usecases.GetRiskProfileUseCase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class BeanConfig {

    @Inject
    MockRiskAdapter mockRiskAdapter;

    @Produces
    @ApplicationScoped
    public GetRiskProfileUseCase getRiskProfileUseCase() {
        return new GetRiskProfileUseCase(mockRiskAdapter);
    }

    @Produces
    @ApplicationScoped
    public RiskPort riskPort() {
        return mockRiskAdapter;
    }
}
```

## 9. `application.properties` — `infrastructure/entry-points/app/src/main/resources`

```properties
quarkus.http.port=8081

# OpenAPI / Swagger
mp.openapi.extensions.smallrye.info.version=1.0.0
quarkus.swagger-ui.always-include=true
quarkus.swagger-ui.path=/swagger-ui

# Health
quarkus.smallrye-health.root-path=/q/health
```

## 10. Levantar en modo dev

```bash
cd backend/ms-risk/infrastructure/entry-points/app
mvn quarkus:dev
```

## Verificación

```bash
# Score (espera ~2s)
curl -s http://localhost:8081/v1/risk/score/1713175071 | jq .
# {"cedula":"1713175071","score":82,"timestamp":"2026-..."}

# Deudas (espera ~1.5s)
curl -s http://localhost:8081/v1/risk/debts/1713175071 | jq .
# {"cedula":"1713175071","deudas":[...],"totalMensual":225.50,...}

# Cédula inválida → 400
curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/v1/risk/score/abc
# 400

# Health
curl -s http://localhost:8081/q/health | jq .status
# "UP"

# Swagger UI
# http://localhost:8081/swagger-ui
```

---

## Pruebas

### Dependencias de test — `ms-risk/pom.xml` (raíz)

> `quarkus-junit5`, `assertj-core` y `rest-assured` ya vienen en el root POM generado por el scaffold — no es necesario declararlos de nuevo.

### Pruebas Unitarias — `MockRiskAdapterTest.java`

Ubicación: `infrastructure/driven-adapters/postgres/src/test/java/com/msrisk/postgres/repository/`

```java
package com.msrisk.postgres.repository;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class MockRiskAdapterTest {

    private final MockRiskAdapter adapter = new MockRiskAdapter();

    @RepeatedTest(20)
    void getScore_siempre_retorna_valor_entre_0_y_100() {
        int score = adapter.getScore("1713175071");
        assertThat(score).isBetween(0, 100);
    }

    @Test
    void getProfile_retorna_cedula_correcta() {
        var profile = adapter.getProfile("1713175071");
        assertThat(profile.cedula()).isEqualTo("1713175071");
    }

    @Test
    void getProfile_lista_de_deudas_no_es_nula() {
        var profile = adapter.getProfile("1713175071");
        assertThat(profile.debts()).isNotNull();
    }

    @Test
    void getProfile_totalMonthlyDebt_es_suma_exacta_de_deudas_individuales() {
        var profile = adapter.getProfile("1713175071");
        var sumaManual = profile.debts().stream()
                .map(d -> d.monthlyPayment())
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        assertThat(profile.totalMonthlyDebt()).isEqualByComparingTo(sumaManual);
    }

    @RepeatedTest(10)
    void multiples_llamadas_producen_variabilidad_en_score() {
        // El score es aleatorio: no debe ser siempre el mismo valor
        // (este test verifica que el RNG funciona, no que sea verdaderamente aleatorio)
        int score = adapter.getScore("0912345678");
        assertThat(score).isBetween(0, 100);
    }
}
```

### Pruebas de Integración — `RiskResourceIT.java`

Ubicación: `infrastructure/entry-points/rest-api/src/test/java/com/msrisk/restapi/resource/`

```java
package com.msrisk.restapi.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class RiskResourceIT {

    private static final String CEDULA_VALIDA = "1713175071";

    // ── /v1/risk/score ────────────────────────────────────────

    @Test
    void score_retorna_200_con_score_en_rango() {
        given()
            .when().get("/v1/risk/score/" + CEDULA_VALIDA)
            .then()
                .statusCode(200)
                .body("cedula", equalTo(CEDULA_VALIDA))
                .body("score", allOf(greaterThanOrEqualTo(0), lessThanOrEqualTo(100)))
                .body("timestamp", notNullValue());
    }

    @Test
    void score_con_cedula_de_longitud_incorrecta_retorna_400() {
        given()
            .when().get("/v1/risk/score/123")
            .then()
                .statusCode(400);
    }

    @Test
    void score_con_cedula_con_letras_retorna_400() {
        given()
            .when().get("/v1/risk/score/ABCDE12345")
            .then()
                .statusCode(400);
    }

    // ── /v1/risk/debts ────────────────────────────────────────

    @Test
    void debts_retorna_200_con_estructura_correcta() {
        given()
            .when().get("/v1/risk/debts/" + CEDULA_VALIDA)
            .then()
                .statusCode(200)
                .body("cedula", equalTo(CEDULA_VALIDA))
                .body("deudas", notNullValue())
                .body("totalMensual", notNullValue())
                .body("timestamp", notNullValue());
    }

    @Test
    void debts_totalMensual_es_consistente_con_lista() {
        var response = given()
            .when().get("/v1/risk/debts/" + CEDULA_VALIDA)
            .then()
                .statusCode(200)
                .extract().body().jsonPath();

        // totalMensual debe ser >= 0
        float total = response.getFloat("totalMensual");
        assertThat(total).isGreaterThanOrEqualTo(0f);
    }

    @Test
    void debts_con_cedula_invalida_retorna_400() {
        given()
            .when().get("/v1/risk/debts/noescedula")
            .then()
                .statusCode(400);
    }

    // ── Health ────────────────────────────────────────────────

    @Test
    void health_endpoint_retorna_UP() {
        given()
            .when().get("/q/health")
            .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }
}
```

### Ejecutar tests

```bash
cd backend/ms-risk

# Unitarios
mvn test -pl infrastructure/driven-adapters/postgres

# Integración (levanta Quarkus en modo test)
mvn test -pl infrastructure/entry-points/rest-api

# Todos
mvn test
```

---

## Estado esperado al finalizar
- [ ] `ms-risk` arranca en puerto 8081
- [ ] `GET /v1/risk/score/{cedula}` retorna score 0–100 con ~2s de latencia
- [ ] `GET /v1/risk/debts/{cedula}` retorna lista de deudas con ~1.5s de latencia
- [ ] Cédula con formato inválido → HTTP 400
- [ ] `/q/health` responde UP
- [ ] Swagger UI accesible en http://localhost:8081/swagger-ui
- [ ] `mvn test` pasa sin errores (unitarios + integración)
