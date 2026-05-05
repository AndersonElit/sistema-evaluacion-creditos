# Paso 04 — ms-credit-evaluation: Dominio (Value Objects, Agregado, Puertos)

## Objetivo
Generar el scaffold de `ms-credit-evaluation` e implementar el Core Domain:
Value Objects con invariantes de negocio, el Agregado raíz `EvaluacionCredito`
y los puertos (interfaces) que definen las dependencias externas.
Ninguna dependencia de infraestructura en esta capa.

## Prerrequisitos
- Paso 01 completado (PostgreSQL disponible)
- Paso 03 completado (ms-risk corriendo, se usará después)
- jbang, Java 21+, Maven

## 1. Generar Scaffold

```bash
cd backend/
jbang ../scaffold/MavenHexagonalScaffold.java -n ms-credit-evaluation -m sqs-producer
```

## 2. Value Object — `Cedula`

### `Cedula.java`
```java
package com.mscreditevaluation.model.valueobject;

public record Cedula(String valor) {

    public Cedula {
        if (valor == null || !valor.matches("\\d{10}")) {
            throw new IllegalArgumentException("Cédula debe tener 10 dígitos numéricos");
        }
        int provincia = Integer.parseInt(valor.substring(0, 2));
        if (provincia < 1 || provincia > 24) {
            throw new IllegalArgumentException("Código de provincia inválido: " + provincia);
        }
        if (!pasaModulo10(valor)) {
            throw new IllegalArgumentException("Cédula inválida: falla Módulo 10");
        }
    }

    private static boolean pasaModulo10(String cedula) {
        int[] coeficientes = {2, 1, 2, 1, 2, 1, 2, 1, 2};
        int suma = 0;
        for (int i = 0; i < 9; i++) {
            int val = Character.getNumericValue(cedula.charAt(i)) * coeficientes[i];
            suma += val >= 10 ? val - 9 : val;
        }
        int digitoVerificador = Character.getNumericValue(cedula.charAt(9));
        int esperado = suma % 10 == 0 ? 0 : 10 - (suma % 10);
        return esperado == digitoVerificador;
    }

    @Override
    public String toString() {
        return valor;
    }
}
```

### `Dinero.java`
```java
package com.mscreditevaluation.model.valueobject;

import java.math.BigDecimal;

public record Dinero(BigDecimal cantidad, String moneda) {

    public static final String USD = "USD";

    public Dinero {
        if (cantidad == null || cantidad.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("El monto debe ser positivo");
        }
        if (moneda == null || moneda.isBlank()) {
            throw new IllegalArgumentException("La moneda es requerida");
        }
    }

    public static Dinero usd(BigDecimal cantidad) {
        return new Dinero(cantidad, USD);
    }

    public Dinero sumar(Dinero otro) {
        return new Dinero(this.cantidad.add(otro.cantidad), this.moneda);
    }

    public boolean menorQue(Dinero otro) {
        return this.cantidad.compareTo(otro.cantidad) < 0;
    }
}
```

### `ScoreRiesgo.java`
```java
package com.mscreditevaluation.model.valueobject;

public record ScoreRiesgo(int valor) {

    public ScoreRiesgo {
        if (valor < 0 || valor > 100) {
            throw new IllegalArgumentException("Score debe estar entre 0 y 100");
        }
    }

    public boolean esSuficiente() {
        return valor > 70;
    }
}
```

### `EstadoEvaluacion.java`
```java
package com.mscreditevaluation.model.entity;

public enum EstadoEvaluacion {
    APROBADO, RECHAZADO, PENDIENTE
}
```

## 3. Agregado Raíz — `EvaluacionCredito`

### `EvaluacionCredito.java`
```java
package com.mscreditevaluation.model.entity;

import com.mscreditevaluation.model.valueobject.Cedula;
import com.mscreditevaluation.model.valueobject.Dinero;
import com.mscreditevaluation.model.valueobject.ScoreRiesgo;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class EvaluacionCredito {

    private final UUID id;
    private final Cedula cedula;
    private final Dinero montoSolicitado;
    private final int plazoAnios;
    private final Dinero salario;
    private final ScoreRiesgo scoreRiesgo;
    private final Dinero deudaMensual;
    private final EstadoEvaluacion estadoFinal;
    private final Instant fechaEvaluacion;
    private final UUID evaluadoPorId;  // sub claim JWT Keycloak

    private static final BigDecimal FACTOR_CAPACIDAD = new BigDecimal("0.40");

    private EvaluacionCredito(Builder b) {
        this.id = b.id;
        this.cedula = b.cedula;
        this.montoSolicitado = b.montoSolicitado;
        this.plazoAnios = b.plazoAnios;
        this.salario = b.salario;
        this.scoreRiesgo = b.scoreRiesgo;
        this.deudaMensual = b.deudaMensual;
        this.estadoFinal = b.estadoFinal;
        this.fechaEvaluacion = b.fechaEvaluacion;
        this.evaluadoPorId = b.evaluadoPorId;
    }

    // Regla de negocio central
    public static EstadoEvaluacion evaluar(ScoreRiesgo score, Dinero deudaMensual,
                                            Dinero salario, Dinero montoSolicitado,
                                            int plazoAnios) {
        if (!score.esSuficiente()) {
            return EstadoEvaluacion.RECHAZADO;
        }
        BigDecimal cuotaNueva = montoSolicitado.cantidad()
                .divide(BigDecimal.valueOf((long) plazoAnios * 12), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal cargaTotal = deudaMensual.cantidad().add(cuotaNueva);
        BigDecimal capacidadMaxima = salario.cantidad().multiply(FACTOR_CAPACIDAD);

        return cargaTotal.compareTo(capacidadMaxima) < 0
                ? EstadoEvaluacion.APROBADO
                : EstadoEvaluacion.RECHAZADO;
    }

    // Getters
    public UUID getId() { return id; }
    public Cedula getCedula() { return cedula; }
    public Dinero getMontoSolicitado() { return montoSolicitado; }
    public int getPlazoAnios() { return plazoAnios; }
    public Dinero getSalario() { return salario; }
    public ScoreRiesgo getScoreRiesgo() { return scoreRiesgo; }
    public Dinero getDeudaMensual() { return deudaMensual; }
    public EstadoEvaluacion getEstadoFinal() { return estadoFinal; }
    public Instant getFechaEvaluacion() { return fechaEvaluacion; }
    public UUID getEvaluadoPorId() { return evaluadoPorId; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID id = UUID.randomUUID();
        private Cedula cedula;
        private Dinero montoSolicitado;
        private int plazoAnios;
        private Dinero salario;
        private ScoreRiesgo scoreRiesgo;
        private Dinero deudaMensual;
        private EstadoEvaluacion estadoFinal;
        private Instant fechaEvaluacion = Instant.now();
        private UUID evaluadoPorId;

        public Builder cedula(Cedula v) { this.cedula = v; return this; }
        public Builder montoSolicitado(Dinero v) { this.montoSolicitado = v; return this; }
        public Builder plazoAnios(int v) { this.plazoAnios = v; return this; }
        public Builder salario(Dinero v) { this.salario = v; return this; }
        public Builder scoreRiesgo(ScoreRiesgo v) { this.scoreRiesgo = v; return this; }
        public Builder deudaMensual(Dinero v) { this.deudaMensual = v; return this; }
        public Builder estadoFinal(EstadoEvaluacion v) { this.estadoFinal = v; return this; }
        public Builder evaluadoPorId(UUID v) { this.evaluadoPorId = v; return this; }
        public EvaluacionCredito build() { return new EvaluacionCredito(this); }
    }
}
```

## 4. Puertos (Interfaces) — `domain/model`

### `EvaluacionCreditoRepository.java`
```java
package com.mscreditevaluation.model.port;

import com.mscreditevaluation.model.entity.EvaluacionCredito;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvaluacionCreditoRepository {
    EvaluacionCredito guardar(EvaluacionCredito evaluacion);
    Optional<EvaluacionCredito> buscarPorId(UUID id);
    List<EvaluacionCredito> listarTodas(int page, int size);
}
```

### `RiskServicePort.java`
```java
package com.mscreditevaluation.model.port;

import java.math.BigDecimal;
import java.util.List;

public interface RiskServicePort {
    record RiskData(int score, BigDecimal totalDeudaMensual) {}
    RiskData consultarRiesgo(String cedula);  // llamadas paralelas internas
}
```

### `NotificationPort.java`
```java
package com.mscreditevaluation.model.port;

import com.mscreditevaluation.model.entity.EvaluacionCredito;

public interface NotificationPort {
    void publicarEvaluacionCompletada(EvaluacionCredito evaluacion, String destinatarioEmail);
}
```

## Verificación (solo compilación — sin infraestructura)

```bash
# Compilar los módulos de dominio y aplicación
cd backend/ms-credit-evaluation
mvn compile -pl domain/model,application/use-cases
# BUILD SUCCESS sin errores
```

---

## Pruebas Unitarias del Dominio

> El dominio no tiene dependencias de infraestructura: los tests son JUnit puro, sin Quarkus ni Mockito.

### Dependencias — `domain/model/pom.xml`

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.2</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <version>3.25.3</version>
    <scope>test</scope>
</dependency>
```

### `CedulaTest.java`

Ubicación: `domain/model/src/test/java/com/mscreditevaluation/model/valueobject/`

```java
package com.mscreditevaluation.model.valueobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class CedulaTest {

    // ── Válidas ───────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"1713175071", "0912345678"})
    void cedulas_validas_son_aceptadas(String valor) {
        assertThatNoException().isThrownBy(() -> new Cedula(valor));
    }

    @Test
    void valor_es_accesible_tras_construccion() {
        assertThat(new Cedula("1713175071").valor()).isEqualTo("1713175071");
    }

    // ── Formato ───────────────────────────────────────────────

    @Test
    void cedula_nula_lanza_excepcion() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Cedula(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"123456789", "17131750711", "ABCDEFGHIJ", "1713 75071", ""})
    void formato_invalido_es_rechazado(String valor) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Cedula(valor));
    }

    // ── Provincia ─────────────────────────────────────────────

    @Test
    void provincia_00_es_rechazada() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Cedula("0013175071"))
                .withMessageContaining("provincia");
    }

    @Test
    void provincia_25_es_rechazada() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Cedula("2500000000"))
                .withMessageContaining("provincia");
    }

    // ── Módulo 10 ─────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"1234567890", "1713175072", "9999999999"})
    void digito_verificador_incorrecto_es_rechazado(String valor) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Cedula(valor));
    }

    // ── Seguridad: inyección SQL rechazada a nivel de dominio ──

    @ParameterizedTest
    @ValueSource(strings = {
        "1' OR '1'='1",
        "'; DROP TABLE t;--",
        "<script>alert(1)</script>",
        "${7*7}"
    })
    void payloads_inyeccion_son_rechazados(String payload) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Cedula(payload));
    }
}
```

### `DineroTest.java`

Ubicación: `domain/model/src/test/java/com/mscreditevaluation/model/valueobject/`

```java
package com.mscreditevaluation.model.valueobject;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class DineroTest {

    @Test
    void monto_positivo_minimo_es_aceptado() {
        assertThatNoException().isThrownBy(() -> Dinero.usd(new BigDecimal("0.01")));
    }

    @Test
    void monto_cero_es_rechazado() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Dinero.usd(BigDecimal.ZERO))
                .withMessageContaining("positivo");
    }

    @Test
    void monto_negativo_es_rechazado() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Dinero.usd(new BigDecimal("-1")));
    }

    @Test
    void monto_nulo_es_rechazado() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Dinero(null, "USD"));
    }

    @Test
    void moneda_vacia_es_rechazada() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Dinero(new BigDecimal("100"), ""));
    }

    @Test
    void sumar_retorna_suma_correcta() {
        var resultado = Dinero.usd(new BigDecimal("100.00"))
                .sumar(Dinero.usd(new BigDecimal("50.50")));
        assertThat(resultado.cantidad()).isEqualByComparingTo("150.50");
    }

    @Test
    void menorQue_es_correcto() {
        var menor = Dinero.usd(new BigDecimal("99"));
        var mayor = Dinero.usd(new BigDecimal("100"));
        assertThat(menor.menorQue(mayor)).isTrue();
        assertThat(mayor.menorQue(menor)).isFalse();
    }
}
```

### `ScoreRiesgoTest.java`

Ubicación: `domain/model/src/test/java/com/mscreditevaluation/model/valueobject/`

```java
package com.mscreditevaluation.model.valueobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class ScoreRiesgoTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 70, 71, 99, 100})
    void scores_en_rango_son_aceptados(int valor) {
        assertThatNoException().isThrownBy(() -> new ScoreRiesgo(valor));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101, Integer.MAX_VALUE})
    void scores_fuera_de_rango_son_rechazados(int valor) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ScoreRiesgo(valor));
    }

    @Test
    void score_71_es_suficiente() {
        assertThat(new ScoreRiesgo(71).esSuficiente()).isTrue();
    }

    @Test
    void score_70_NO_es_suficiente_limite_estricto() {
        // La regla es score > 70 (estricto, no >=)
        assertThat(new ScoreRiesgo(70).esSuficiente()).isFalse();
    }

    @Test
    void score_0_no_es_suficiente() {
        assertThat(new ScoreRiesgo(0).esSuficiente()).isFalse();
    }

    @Test
    void score_100_es_suficiente() {
        assertThat(new ScoreRiesgo(100).esSuficiente()).isTrue();
    }
}
```

### `EvaluacionCreditoTest.java` — Regla de Negocio

Ubicación: `domain/model/src/test/java/com/mscreditevaluation/model/entity/`

```java
package com.mscreditevaluation.model.entity;

import com.mscreditevaluation.model.valueobject.Cedula;
import com.mscreditevaluation.model.valueobject.Dinero;
import com.mscreditevaluation.model.valueobject.ScoreRiesgo;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class EvaluacionCreditoTest {

    private static final Dinero SALARIO_2000  = Dinero.usd(new BigDecimal("2000.00"));
    private static final Dinero MONTO_5000    = Dinero.usd(new BigDecimal("5000.00"));
    private static final Dinero DEUDA_200     = Dinero.usd(new BigDecimal("200.00"));
    private static final Dinero DEUDA_MINIMA  = Dinero.usd(new BigDecimal("0.01"));

    // cuota = 5000 / (3*12) ≈ 138.89
    // carga = 200 + 138.89 = 338.89 < salario*0.40 = 800 → APROBADO si score > 70

    @Test
    void aprobado_score_alto_deuda_baja() {
        // BDD: score 85, deuda 200, salario 2000, monto 5000, plazo 3 → APROBADO
        var estado = EvaluacionCredito.evaluar(
                new ScoreRiesgo(85), DEUDA_200, SALARIO_2000, MONTO_5000, 3);
        assertThat(estado).isEqualTo(EstadoEvaluacion.APROBADO);
    }

    @Test
    void rechazado_score_igual_70_limite_estricto() {
        // BDD: score = 70 → RECHAZADO (regla > 70, no >=)
        var estado = EvaluacionCredito.evaluar(
                new ScoreRiesgo(70), DEUDA_200, SALARIO_2000, MONTO_5000, 3);
        assertThat(estado).isEqualTo(EstadoEvaluacion.RECHAZADO);
    }

    @Test
    void rechazado_score_menor_70() {
        var estado = EvaluacionCredito.evaluar(
                new ScoreRiesgo(45), DEUDA_200, SALARIO_2000, MONTO_5000, 3);
        assertThat(estado).isEqualTo(EstadoEvaluacion.RECHAZADO);
    }

    @Test
    void rechazado_carga_supera_40_porciento_aunque_score_sea_alto() {
        // salario 1000 → límite 400; monto 10000/(2*12)≈416 + deuda 300 = 716 > 400
        var salarioBajo = Dinero.usd(new BigDecimal("1000.00"));
        var montoAlto   = Dinero.usd(new BigDecimal("10000.00"));
        var deudaAlta   = Dinero.usd(new BigDecimal("300.00"));
        var estado = EvaluacionCredito.evaluar(
                new ScoreRiesgo(90), deudaAlta, salarioBajo, montoAlto, 2);
        assertThat(estado).isEqualTo(EstadoEvaluacion.RECHAZADO);
    }

    @Test
    void aprobado_en_limite_exacto_score_71_deuda_minima() {
        // BDD: score 71, monto 1000, salario 2000, plazo 1, deuda ≈0 → APROBADO
        var monto1000 = Dinero.usd(new BigDecimal("1000.00"));
        var estado = EvaluacionCredito.evaluar(
                new ScoreRiesgo(71), DEUDA_MINIMA, SALARIO_2000, monto1000, 1);
        assertThat(estado).isEqualTo(EstadoEvaluacion.APROBADO);
    }

    @Test
    void builder_genera_id_unico_por_instancia() {
        var e1 = EvaluacionCredito.builder()
                .cedula(new Cedula("1713175071")).montoSolicitado(MONTO_5000)
                .plazoAnios(3).salario(SALARIO_2000)
                .scoreRiesgo(new ScoreRiesgo(85)).deudaMensual(DEUDA_200)
                .estadoFinal(EstadoEvaluacion.APROBADO).build();
        var e2 = EvaluacionCredito.builder()
                .cedula(new Cedula("1713175071")).montoSolicitado(MONTO_5000)
                .plazoAnios(3).salario(SALARIO_2000)
                .scoreRiesgo(new ScoreRiesgo(85)).deudaMensual(DEUDA_200)
                .estadoFinal(EstadoEvaluacion.APROBADO).build();

        assertThat(e1.getId()).isNotEqualTo(e2.getId());
    }

    @Test
    void builder_asigna_fecha_de_evaluacion_automaticamente() {
        var e = EvaluacionCredito.builder()
                .cedula(new Cedula("1713175071")).montoSolicitado(MONTO_5000)
                .plazoAnios(3).salario(SALARIO_2000)
                .scoreRiesgo(new ScoreRiesgo(80)).deudaMensual(DEUDA_200)
                .estadoFinal(EstadoEvaluacion.APROBADO).build();
        assertThat(e.getFechaEvaluacion()).isNotNull();
    }
}
```

### Ejecutar tests del dominio

```bash
cd backend/ms-credit-evaluation
mvn test -pl domain/model
# Resultado esperado: BUILD SUCCESS — 20+ tests en verde
```

---

## Estado esperado al finalizar
- [ ] Scaffold `ms-credit-evaluation` generado con módulo sqs-producer
- [ ] `Cedula` valida Módulo 10 ecuatoriano con invariantes en el constructor
- [ ] `Dinero` rechaza montos <= 0
- [ ] `ScoreRiesgo` valida rango 0–100
- [ ] `EvaluacionCredito.evaluar()` implementa regla: score > 70 AND carga < salario × 0.40
- [ ] Tres puertos definidos: Repository, RiskService, Notification
- [ ] Módulos `domain/model` y `application/use-cases` compilan sin errores
- [ ] `mvn test -pl domain/model` pasa con 20+ tests en verde
