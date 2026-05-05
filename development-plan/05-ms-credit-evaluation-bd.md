# Paso 05 — ms-credit-evaluation: Base de Datos y Adaptador PostgreSQL

## Objetivo
Implementar el adaptador de persistencia para `ms-credit-evaluation`:
migración manual del DDL de `creditos_db` con psql, entidad JPA Panache y repositorio
que implementa `EvaluacionCreditoRepository`.

## Prerrequisitos
- Paso 01 completado (`postgres-credits` corriendo en puerto 5432)
- Paso 04 completado (dominio con puertos definidos)

## 1. Dependencias — `infrastructure/driven-adapters/postgres/pom.xml`

El scaffold ya genera las dependencias reactivas correctas — **no reemplazar** por JPA bloqueante.
Solo agregar la dependencia al módulo de dominio:

```xml
<!-- Generadas por el scaffold (mantener) -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-hibernate-reactive-panache</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-reactive-pg-client</artifactId>
</dependency>
<!-- Agregar manualmente -->
<dependency>
    <groupId>com.mscreditevaluation</groupId>
    <artifactId>domain-model</artifactId>
    <version>${project.version}</version>
</dependency>
```

## 2. Migración manual — `creditos_db`

Aplicar el DDL directamente sobre la base de datos **antes** de levantar el servicio:

```bash
psql -h localhost -p 5432 -U postgres -d creditos_db \
  -f infrastructure/entry-points/app/src/main/resources/db/migration/V1__create_credit_evaluations.sql
```

Crear la carpeta y el archivo de migración:

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

## 3. Entidad JPA Reactiva — `infrastructure/driven-adapters/postgres`

### `CreditEvaluationEntity.java`
```java
package com.mscreditevaluation.postgres.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;

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

## 4. Repositorio Reactivo — `infrastructure/driven-adapters/postgres`

### `CreditEvaluationRepositoryAdapter.java`
```java
package com.mscreditevaluation.postgres.repository;

import com.mscreditevaluation.model.entity.EstadoEvaluacion;
import com.mscreditevaluation.model.entity.EvaluacionCredito;
import com.mscreditevaluation.model.port.EvaluacionCreditoRepository;
import com.mscreditevaluation.model.valueobject.Cedula;
import com.mscreditevaluation.model.valueobject.Dinero;
import com.mscreditevaluation.model.valueobject.ScoreRiesgo;
import com.mscreditevaluation.postgres.entity.CreditEvaluationEntity;
import io.quarkus.hibernate.reactive.panache.common.ReactiveTransactional;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CreditEvaluationRepositoryAdapter implements EvaluacionCreditoRepository {

    @Override
    @ReactiveTransactional
    public Uni<EvaluacionCredito> guardar(EvaluacionCredito evaluacion) {
        CreditEvaluationEntity entity = toEntity(evaluacion);
        return entity.<CreditEvaluationEntity>persistAndFlush()
                .map(e -> evaluacion);
    }

    @Override
    public Uni<Optional<EvaluacionCredito>> buscarPorId(UUID id) {
        return CreditEvaluationEntity.<CreditEvaluationEntity>findById(id)
                .map(e -> Optional.ofNullable(e).map(this::toDomain));
    }

    @Override
    public Uni<List<EvaluacionCredito>> listarTodas(int page, int size) {
        return CreditEvaluationEntity.<CreditEvaluationEntity>findAll()
                .page(page, size)
                .list()
                .map(list -> list.stream().map(this::toDomain).toList());
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

# ── DataSource Reactivo — creditos_db ────────────────────────
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=${DB_USERNAME:postgres}
quarkus.datasource.password=${DB_PASSWORD:postgres}
quarkus.datasource.reactive.url=postgresql://${DB_HOST:localhost}:5432/creditos_db

# ── Hibernate Reactive ───────────────────────────────────────
quarkus.hibernate-orm.database.generation=validate
```

## 6. Aplicar migración y levantar

```bash
# 1. Aplicar el DDL manualmente antes de arrancar el servicio
psql -h localhost -p 5432 -U postgres -d creditos_db \
  -f infrastructure/entry-points/app/src/main/resources/db/migration/V1__create_credit_evaluations.sql

# 2. Levantar el servicio
cd backend/ms-credit-evaluation/infrastructure/entry-points/app
mvn quarkus:dev
```

## Verificación

```bash
# Verificar tabla e índices
psql -h localhost -p 5432 -U postgres -d creditos_db \
  -c "\d credit_evaluations"

psql -h localhost -p 5432 -U postgres -d creditos_db \
  -c "\di idx_credit_eval_*"

# Health del servicio
curl -s http://localhost:8080/q/health | jq .status
# "UP"
```

---

## Prueba de Integración — Repositorio con Testcontainers

> Levanta un PostgreSQL real en Docker durante el test — no requiere la base de datos del paso 01.

### Dependencias adicionales — `infrastructure/driven-adapters/postgres/pom.xml`

> `quarkus-junit5` ya está en el root POM generado por el scaffold. Solo agregar:

```xml
<!-- PostgreSQL real vía DevServices (levanta contenedor automáticamente) -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-devservices-postgresql</artifactId>
    <scope>test</scope>
</dependency>
<!-- Soporte para tests reactivos en Vert.x -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-test-vertx</artifactId>
    <scope>test</scope>
</dependency>
```

### `application.properties` de test — `src/test/resources/application.properties`

```properties
# Quarkus DevServices levanta automáticamente un contenedor PostgreSQL para test
quarkus.datasource.db-kind=postgresql
# drop-and-create recrea el esquema desde las entidades — no requiere migración manual en tests
quarkus.hibernate-orm.database.generation=drop-and-create
```

### `CreditEvaluationRepositoryIT.java`

Ubicación: `infrastructure/driven-adapters/postgres/src/test/java/com/mscreditevaluation/postgres/repository/`

```java
package com.mscreditevaluation.postgres.repository;

import com.mscreditevaluation.model.entity.EstadoEvaluacion;
import com.mscreditevaluation.model.entity.EvaluacionCredito;
import com.mscreditevaluation.model.valueobject.Cedula;
import com.mscreditevaluation.model.valueobject.Dinero;
import com.mscreditevaluation.model.valueobject.ScoreRiesgo;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.TestReactiveTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
@RunOnVertxContext
class CreditEvaluationRepositoryIT {

    @Inject
    CreditEvaluationRepositoryAdapter repository;

    private EvaluacionCredito evaluacionValida() {
        return EvaluacionCredito.builder()
                .cedula(new Cedula("1713175071"))
                .montoSolicitado(Dinero.usd(new BigDecimal("5000.00")))
                .plazoAnios(3)
                .salario(Dinero.usd(new BigDecimal("2000.00")))
                .scoreRiesgo(new ScoreRiesgo(85))
                .deudaMensual(Dinero.usd(new BigDecimal("200.00")))
                .estadoFinal(EstadoEvaluacion.APROBADO)
                .evaluadoPorId(UUID.randomUUID())
                .build();
    }

    @Test
    @TestReactiveTransaction
    Uni<Void> guardar_persiste_y_asigna_id() {
        return repository.guardar(evaluacionValida())
                .invoke(guardada -> {
                    assertThat(guardada.getId()).isNotNull();
                    assertThat(guardada.getCedula().valor()).isEqualTo("1713175071");
                    assertThat(guardada.getEstadoFinal()).isEqualTo(EstadoEvaluacion.APROBADO);
                }).replaceWithVoid();
    }

    @Test
    @TestReactiveTransaction
    Uni<Void> buscarPorId_retorna_evaluacion_persistida() {
        var evaluacion = evaluacionValida();
        return repository.guardar(evaluacion)
                .chain(e -> repository.buscarPorId(e.getId()))
                .invoke(encontrada -> {
                    assertThat(encontrada).isPresent();
                    assertThat(encontrada.get().getCedula().valor()).isEqualTo("1713175071");
                    assertThat(encontrada.get().getScoreRiesgo().valor()).isEqualTo(85);
                }).replaceWithVoid();
    }

    @Test
    @TestReactiveTransaction
    Uni<Void> buscarPorId_retorna_empty_para_id_inexistente() {
        return repository.buscarPorId(UUID.randomUUID())
                .invoke(resultado -> assertThat(resultado).isEmpty())
                .replaceWithVoid();
    }

    @Test
    @TestReactiveTransaction
    Uni<Void> listarTodas_retorna_evaluaciones_paginadas() {
        return Uni.combine().all().unis(
                repository.guardar(evaluacionValida()),
                repository.guardar(evaluacionValida()),
                repository.guardar(evaluacionValida()))
            .discardItems()
            .chain(() -> repository.listarTodas(0, 10))
            .invoke(lista -> assertThat(lista).hasSizeGreaterThanOrEqualTo(3))
            .replaceWithVoid();
    }

    @Test
    @TestReactiveTransaction
    Uni<Void> evaluacion_rechazada_se_persiste_con_estado_correcto() {
        var evaluacion = EvaluacionCredito.builder()
                .cedula(new Cedula("1713175071"))
                .montoSolicitado(Dinero.usd(new BigDecimal("10000.00")))
                .plazoAnios(2)
                .salario(Dinero.usd(new BigDecimal("1000.00")))
                .scoreRiesgo(new ScoreRiesgo(45))
                .deudaMensual(Dinero.usd(new BigDecimal("400.00")))
                .estadoFinal(EstadoEvaluacion.RECHAZADO)
                .evaluadoPorId(UUID.randomUUID())
                .build();

        return repository.guardar(evaluacion)
                .chain(e -> repository.buscarPorId(e.getId()))
                .invoke(encontrada -> {
                    assertThat(encontrada).isPresent();
                    assertThat(encontrada.get().getEstadoFinal()).isEqualTo(EstadoEvaluacion.RECHAZADO);
                }).replaceWithVoid();
    }
}
```

### Ejecutar

```bash
cd backend/ms-credit-evaluation
# Requiere Docker para DevServices (levanta PostgreSQL automáticamente)
mvn test -pl infrastructure/driven-adapters/postgres
```

---

## Estado esperado al finalizar
- [ ] `V1__create_credit_evaluations.sql` aplicado manualmente con psql antes de arrancar
- [ ] Tabla `credit_evaluations` creada con todos los campos e índices
- [ ] ENUM `estado_evaluacion` creado en PostgreSQL
- [ ] `CreditEvaluationRepositoryAdapter` implementa `EvaluacionCreditoRepository`
- [ ] El servicio arranca sin errores en modo dev
- [ ] `CreditEvaluationRepositoryIT` pasa: guardar, buscar, listar, rechazada
