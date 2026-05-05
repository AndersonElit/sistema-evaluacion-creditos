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
jbang scaffold/MavenHexagonalScaffold.java -n ms-credit-evaluation -m sqs-producer
```

## 2. Value Object — `Cedula`

### `Cedula.java`
```java
package com.mscreditevaluation.model;

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
package com.mscreditevaluation.model;

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
package com.mscreditevaluation.model;

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
package com.mscreditevaluation.model;

public enum EstadoEvaluacion {
    APROBADO, RECHAZADO, PENDIENTE
}
```

## 3. Agregado Raíz — `EvaluacionCredito`

### `EvaluacionCredito.java`
```java
package com.mscreditevaluation.model;

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
package com.mscreditevaluation.model;

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
package com.mscreditevaluation.model;

import java.math.BigDecimal;
import java.util.List;

public interface RiskServicePort {
    record RiskData(int score, BigDecimal totalDeudaMensual) {}
    RiskData consultarRiesgo(String cedula);  // llamadas paralelas internas
}
```

### `NotificationPort.java`
```java
package com.mscreditevaluation.model;

public interface NotificationPort {
    void publicarEvaluacionCompletada(EvaluacionCredito evaluacion, String destinatarioEmail);
}
```

## Verificación (solo compilación — sin infraestructura)

```bash
# Compilar los módulos de dominio y aplicación
cd ms-credit-evaluation
mvn compile -pl domain/model,application/use-cases
# BUILD SUCCESS sin errores
```

## Estado esperado al finalizar
- [ ] Scaffold `ms-credit-evaluation` generado con módulo sqs-producer
- [ ] `Cedula` valida Módulo 10 ecuatoriano con invariantes en el constructor
- [ ] `Dinero` rechaza montos <= 0
- [ ] `ScoreRiesgo` valida rango 0–100
- [ ] `EvaluacionCredito.evaluar()` implementa regla: score > 70 AND carga < salario × 0.40
- [ ] Tres puertos definidos: Repository, RiskService, Notification
- [ ] Módulos `domain/model` y `application/use-cases` compilan sin errores
