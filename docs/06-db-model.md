# Modelo de Base de Datos — PostgreSQL

El sistema utiliza **dos bases de datos propias**, una por cada microservicio con estado propio. Keycloak gestiona su propia base de datos de forma autónoma.

| Base de datos | Propietario | Tablas |
|---------------|-------------|--------|
| `creditos_db` | ms-credit-evaluation | `credit_evaluations` |
| `notifications_db` | ms-notifications | `notifications` |
| `keycloak_db` | Keycloak (gestionada por Keycloak) | Interna — no se modifica directamente |

La columna `evaluado_por_id` en `credit_evaluations` es una **referencia débil al `sub` claim del JWT de Keycloak** (UUID del usuario en Keycloak). No existe foreign key cruzada; la integridad se garantiza a nivel de aplicación.

---

## Base de Datos: `creditos_db` — ms-credit-evaluation

```
┌────────────────────────────────────────────────────┐
│                  credit_evaluations                 │
├────────────────────────────────────────────────────┤
│ PK id                  UUID                        │
│    cedula              VARCHAR(10)  NOT NULL        │
│    monto_solicitado    NUMERIC(15,2) NOT NULL       │
│    plazo_anios         INTEGER      NOT NULL        │
│    salario             NUMERIC(15,2) NOT NULL       │
│    score_riesgo        INTEGER      NOT NULL        │
│    deuda_mensual_total NUMERIC(15,2) NOT NULL       │
│    estado_final        VARCHAR(20)  NOT NULL        │
│    fecha_evaluacion    TIMESTAMPTZ  NOT NULL        │
│    evaluado_por_id     UUID  [sub claim JWT Keycloak] │
└────────────────────────────────────────────────────┘
```

## Base de Datos: `notifications_db` — ms-notifications

```
┌────────────────────────────────────────────────────┐
│                    notifications                    │
├────────────────────────────────────────────────────┤
│ PK id                  UUID                        │
│    evaluacion_id       UUID         NOT NULL        │
│    destinatario_email  VARCHAR(255) NOT NULL        │
│    tipo_notificacion   VARCHAR(20)  NOT NULL        │
│    estado              VARCHAR(20)  NOT NULL        │
│    intentos            INTEGER DEFAULT 0            │
│    enviado_en          TIMESTAMPTZ                  │
│    mensaje_sqs_id      VARCHAR(255)                 │
│    creado_en           TIMESTAMPTZ  NOT NULL        │
└────────────────────────────────────────────────────┘
```

> `evaluacion_id` es una **referencia débil por UUID** a `credit_evaluations` en `creditos_db`. No existe FK cruzada entre bases de datos; la idempotencia se garantiza vía índice único en `evaluacion_id`.

---

## DDL — Scripts de Creación

> **Keycloak** gestiona su propia base de datos internamente. Los roles (`ADMIN`, `ANALYST`, `VIEWER`) y los usuarios se crean vía Keycloak Admin Console o Admin REST API — no requieren DDL manual.

### `creditos_db` — ejecutar en el PostgreSQL de ms-credit-evaluation

```sql
-- ================================================================
-- EXTENSIONES
-- ================================================================
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ================================================================
-- TIPOS ENUMERADOS
-- ================================================================
CREATE TYPE estado_evaluacion AS ENUM ('APROBADO', 'RECHAZADO', 'PENDIENTE');

-- ================================================================
-- TABLA: credit_evaluations
-- ================================================================
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
    evaluado_por_id      UUID           -- sub claim del JWT de Keycloak (ID del usuario en Keycloak)
);

-- ================================================================
-- ÍNDICES DE RENDIMIENTO
-- ================================================================

-- Búsqueda frecuente por cédula
CREATE INDEX idx_credit_eval_cedula
    ON credit_evaluations (cedula);

-- Filtrado por estado
CREATE INDEX idx_credit_eval_estado
    ON credit_evaluations (estado_final);

-- Filtrado por fecha (reportes)
CREATE INDEX idx_credit_eval_fecha
    ON credit_evaluations (fecha_evaluacion DESC);

-- Evaluaciones por usuario (dashboard)
CREATE INDEX idx_credit_eval_usuario
    ON credit_evaluations (evaluado_por_id);
```

---

### `notifications_db` — ejecutar en el PostgreSQL de ms-notifications

```sql
-- ================================================================
-- EXTENSIONES
-- ================================================================
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ================================================================
-- TIPOS ENUMERADOS
-- ================================================================
CREATE TYPE tipo_notif   AS ENUM ('APROBADO', 'RECHAZADO');
CREATE TYPE estado_notif AS ENUM ('PENDIENTE', 'ENVIADO', 'FALLIDO');

-- ================================================================
-- TABLA: notifications
-- ================================================================
CREATE TABLE notifications (
    id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluacion_id        UUID          NOT NULL,   -- ref. débil a creditos_db, sin FK cruzada
    destinatario_email   VARCHAR(255)  NOT NULL,
    tipo_notificacion    tipo_notif    NOT NULL,
    estado               estado_notif  NOT NULL DEFAULT 'PENDIENTE',
    intentos             INTEGER       NOT NULL DEFAULT 0,
    enviado_en           TIMESTAMPTZ,
    mensaje_sqs_id       VARCHAR(255),
    creado_en            TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- ================================================================
-- ÍNDICES DE RENDIMIENTO
-- ================================================================

-- Notificaciones pendientes (polling del scheduler)
CREATE INDEX idx_notifications_estado
    ON notifications (estado)
    WHERE estado = 'PENDIENTE';

-- Idempotencia: una notificación por evaluación
CREATE UNIQUE INDEX idx_notifications_evaluacion_unique
    ON notifications (evaluacion_id);
```

---

## Descripción de Tablas

> **Keycloak** gestiona sus propias tablas internamente (usuarios, credenciales, roles, sesiones). No se describen aquí porque no son accedidas directamente por ningún microservicio del sistema.

### Tablas en `creditos_db` (ms-credit-evaluation)

#### `credit_evaluations`
| Columna | Tipo | Descripción |
|---------|------|-------------|
| `id` | UUID | PK |
| `cedula` | VARCHAR(10) | Cédula ecuatoriana validada |
| `monto_solicitado` | NUMERIC(15,2) | USD con 2 decimales |
| `plazo_anios` | INTEGER | Duración 1–30 años |
| `salario` | NUMERIC(15,2) | Salario mensual declarado |
| `score_riesgo` | INTEGER | Score 0–100 del servicio externo |
| `deuda_mensual_total` | NUMERIC(15,2) | Suma de mensualidades de deudas |
| `estado_final` | ENUM | APROBADO / RECHAZADO / PENDIENTE |
| `fecha_evaluacion` | TIMESTAMPTZ | Momento exacto de la evaluación |
| `evaluado_por_id` | UUID (ref. débil) | `sub` claim del JWT de Keycloak — identifica al analista sin FK cruzada |

---

### Tablas en `notifications_db` (ms-notifications)

#### `notifications`
| Columna | Tipo | Descripción |
|---------|------|-------------|
| `id` | UUID | PK |
| `evaluacion_id` | UUID (ref. débil) | ID de evaluación en `creditos_db` — sin FK cruzada |
| `destinatario_email` | VARCHAR(255) | Email del solicitante |
| `tipo_notificacion` | ENUM | APROBADO / RECHAZADO |
| `estado` | ENUM | PENDIENTE / ENVIADO / FALLIDO |
| `intentos` | INTEGER | Contador de intentos de envío |
| `enviado_en` | TIMESTAMPTZ | Timestamp del envío exitoso |
| `mensaje_sqs_id` | VARCHAR(255) | Message ID de SQS para trazabilidad |
| `creado_en` | TIMESTAMPTZ | Timestamp de creación |

---

## Configuración Quarkus (application.properties)

### ms-credit-evaluation — `creditos_db`

```properties
# ── Datasource ───────────────────────────────────────────────
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=${DB_USERNAME:postgres}
quarkus.datasource.password=${DB_PASSWORD:postgres}
quarkus.datasource.jdbc.url=jdbc:postgresql://${DB_HOST:localhost}:5432/creditos_db

# ── Hibernate ORM ────────────────────────────────────────────
quarkus.hibernate-orm.database.generation=validate

# ── Flyway (migraciones) ─────────────────────────────────────
quarkus.flyway.migrate-at-start=true
quarkus.flyway.locations=classpath:db/migration
```

### ms-notifications — `notifications_db`

```properties
# ── Datasource ───────────────────────────────────────────────
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=${NOTIF_DB_USERNAME:postgres}
quarkus.datasource.password=${NOTIF_DB_PASSWORD:postgres}
quarkus.datasource.jdbc.url=jdbc:postgresql://${NOTIF_DB_HOST:localhost}:5434/notifications_db

# ── Hibernate ORM ────────────────────────────────────────────
quarkus.hibernate-orm.database.generation=validate

# ── Flyway (migraciones) ─────────────────────────────────────
quarkus.flyway.migrate-at-start=true
quarkus.flyway.locations=classpath:db/migration
```

---

## Protección contra SQL Injection

El sistema usa **Hibernate ORM Panache** exclusivamente. Todos los queries usan parámetros nombrados:

```java
// ✅ CORRECTO — Hibernate convierte esto a PreparedStatement
CreditEvaluation.find("cedula = :cedula", Parameters.with("cedula", cedula))

// ❌ NUNCA HACER — vulnerable a SQL injection
CreditEvaluation.find("cedula = '" + cedula + "'")
```

Los `@ColumnDefinition` y `@Check` constraints en las entidades refuerzan la integridad a nivel de BD.
