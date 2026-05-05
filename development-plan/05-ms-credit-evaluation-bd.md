# Paso 05 — ms-credit-evaluation: Base de Datos y Adaptador PostgreSQL

## Objetivo
Implementar el adaptador de persistencia para `ms-credit-evaluation`:
migración Flyway con el DDL de `creditos_db`, entidad JPA Panache y repositorio
que implementa `EvaluacionCreditoRepository`.

## Prerrequisitos
- Paso 01 completado (`postgres-credits` corriendo en puerto 5432)
- Paso 04 completado (dominio con puertos definidos)

## 1. Dependencias — `infrastructure/driven-adapters/postgres/pom.xml`

Agregar las dependencias necesarias (Flyway no vendrá por defecto del scaffold):

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-hibernate-orm-panache</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-jdbc-postgresql</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-flyway</artifactId>
</dependency>
<dependency>
    <groupId>com.mscreditevaluation</groupId>
    <artifactId>domain-model</artifactId>
    <version>${project.version}</version>
</dependency>
```

## 2. Migración Flyway

Crear la carpeta de migraciones en el módulo `app` (donde Quarkus arranca):

```
infrastructure/entry-points/app/src/main/resources/db/migration/
```

### `V1__create_credit_evaluations.sql`
```sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TYPE estado_evaluacion AS ENUM ('APROBADO', 'RECHAZADO', 'PENDIENTE');

CREATE TABLE credit_evaluations (
    id                   UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    cedula               VARCHAR(10)    NOT NULL,
    monto_solicitado     NUMERIC(15, 2) NOT NULL CHECK (monto_solicitado > 0),
    plazo_anios          INTEGER        NOT NULL CHECK (plazo_anios BETWEEN 1 AND 30),
    salario              NUMERIC(15, 2) NOT NULL CHECK (salario > 0),
    score_riesgo         INTEGER        NOT NULL CHECK (score_riesgo BETWEEN 0 AND 100),
    deuda_mensual_total  NUMERIC(15, 2) NOT NULL CHECK (deuda_mensual_total >= 0),
    estado_final         estado_evaluacion NOT NULL DEFAULT 'PENDIENTE',
    fecha_evaluacion     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    evaluado_por_id      UUID
);

CREATE INDEX idx_credit_eval_cedula  ON credit_evaluations (cedula);
CREATE INDEX idx_credit_eval_estado  ON credit_evaluations (estado_final);
CREATE INDEX idx_credit_eval_fecha   ON credit_evaluations (fecha_evaluacion DESC);
CREATE INDEX idx_credit_eval_usuario ON credit_evaluations (evaluado_por_id);
```

## 3. Entidad JPA — `infrastructure/driven-adapters/postgres`

### `CreditEvaluationEntity.java`
```java
package com.mscreditevaluation.postgres;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "credit_evaluations")
public class CreditEvaluationEntity extends PanacheEntityBase {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    public UUID id;

    @Column(name = "cedula", nullable = false, length = 10)
    public String cedula;

    @Column(name = "monto_solicitado", nullable = false, precision = 15, scale = 2)
    public BigDecimal montoSolicitado;

    @Column(name = "plazo_anios", nullable = false)
    public int plazoAnios;

    @Column(name = "salario", nullable = false, precision = 15, scale = 2)
    public BigDecimal salario;

    @Column(name = "score_riesgo", nullable = false)
    public int scoreRiesgo;

    @Column(name = "deuda_mensual_total", nullable = false, precision = 15, scale = 2)
    public BigDecimal deudaMensualTotal;

    @Column(name = "estado_final", nullable = false)
    @Enumerated(EnumType.STRING)
    public EstadoEvaluacionJpa estadoFinal;

    @Column(name = "fecha_evaluacion", nullable = false)
    public Instant fechaEvaluacion;

    @Column(name = "evaluado_por_id", columnDefinition = "uuid")
    public UUID evaluadoPorId;

    public enum EstadoEvaluacionJpa { APROBADO, RECHAZADO, PENDIENTE }
}
```

## 4. Repositorio — `infrastructure/driven-adapters/postgres`

### `CreditEvaluationRepositoryAdapter.java`
```java
package com.mscreditevaluation.postgres;

import com.mscreditevaluation.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CreditEvaluationRepositoryAdapter implements EvaluacionCreditoRepository {

    @Override
    @Transactional
    public EvaluacionCredito guardar(EvaluacionCredito evaluacion) {
        CreditEvaluationEntity entity = toEntity(evaluacion);
        CreditEvaluationEntity.persist(entity);
        return evaluacion;
    }

    @Override
    public Optional<EvaluacionCredito> buscarPorId(UUID id) {
        return CreditEvaluationEntity.<CreditEvaluationEntity>findByIdOptional(id)
                .map(this::toDomain);
    }

    @Override
    public List<EvaluacionCredito> listarTodas(int page, int size) {
        return CreditEvaluationEntity.<CreditEvaluationEntity>findAll()
                .page(page, size)
                .list()
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private CreditEvaluationEntity toEntity(EvaluacionCredito d) {
        var e = new CreditEvaluationEntity();
        e.id = d.getId();
        e.cedula = d.getCedula().valor();
        e.montoSolicitado = d.getMontoSolicitado().cantidad();
        e.plazoAnios = d.getPlazoAnios();
        e.salario = d.getSalario().cantidad();
        e.scoreRiesgo = d.getScoreRiesgo().valor();
        e.deudaMensualTotal = d.getDeudaMensual().cantidad();
        e.estadoFinal = CreditEvaluationEntity.EstadoEvaluacionJpa
                .valueOf(d.getEstadoFinal().name());
        e.fechaEvaluacion = d.getFechaEvaluacion();
        e.evaluadoPorId = d.getEvaluadoPorId();
        return e;
    }

    private EvaluacionCredito toDomain(CreditEvaluationEntity e) {
        return EvaluacionCredito.builder()
                .cedula(new Cedula(e.cedula))
                .montoSolicitado(Dinero.usd(e.montoSolicitado))
                .plazoAnios(e.plazoAnios)
                .salario(Dinero.usd(e.salario))
                .scoreRiesgo(new ScoreRiesgo(e.scoreRiesgo))
                .deudaMensual(Dinero.usd(e.deudaMensualTotal))
                .estadoFinal(EstadoEvaluacion.valueOf(e.estadoFinal.name()))
                .evaluadoPorId(e.evaluadoPorId)
                .build();
    }
}
```

## 5. `application.properties` — configuración BD (solo datasource por ahora)

En `infrastructure/entry-points/app/src/main/resources/application.properties`:

```properties
quarkus.http.port=8080

# ── DataSource — creditos_db ──────────────────────────────────
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=${DB_USERNAME:postgres}
quarkus.datasource.password=${DB_PASSWORD:postgres}
quarkus.datasource.jdbc.url=jdbc:postgresql://${DB_HOST:localhost}:5432/creditos_db

# ── Hibernate ORM ────────────────────────────────────────────
quarkus.hibernate-orm.database.generation=validate

# ── Flyway ──────────────────────────────────────────────────
quarkus.flyway.migrate-at-start=true
quarkus.flyway.locations=classpath:db/migration
```

## 6. Levantar y verificar migración

```bash
cd ms-credit-evaluation/infrastructure/entry-points/app
mvn quarkus:dev
# Flyway aplica V1__create_credit_evaluations.sql automáticamente al arrancar
```

## Verificación

```bash
# Conectar a creditos_db y verificar tabla
psql -h localhost -p 5432 -U postgres -d creditos_db \
  -c "\d credit_evaluations"

# Verificar índices
psql -h localhost -p 5432 -U postgres -d creditos_db \
  -c "\di idx_credit_eval_*"

# Flyway schema_history (migraciones aplicadas)
psql -h localhost -p 5432 -U postgres -d creditos_db \
  -c "SELECT version, description, success FROM flyway_schema_history;"
# V1 | create credit_evaluations | t

# Health del servicio
curl -s http://localhost:8080/q/health | jq .status
# "UP"
```

## Estado esperado al finalizar
- [ ] Flyway aplica `V1__create_credit_evaluations.sql` al arrancar
- [ ] Tabla `credit_evaluations` creada con todos los campos e índices
- [ ] ENUM `estado_evaluacion` creado en PostgreSQL
- [ ] `CreditEvaluationRepositoryAdapter` implementa `EvaluacionCreditoRepository`
- [ ] El servicio arranca sin errores en modo dev
