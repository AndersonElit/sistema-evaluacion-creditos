# Modelo de Base de Datos — PostgreSQL

El sistema utiliza **tres bases de datos independientes**, una por cada microservicio con datos propios:

| Base de datos | Propietario | Tablas |
|---------------|-------------|--------|
| `auth_db` | ms-auth | `users`, `roles`, `user_roles` |
| `creditos_db` | ms-credit-evaluation | `credit_evaluations` |
| `notifications_db` | ms-notifications | `notifications` |

La columna `evaluado_por_id` en `credit_evaluations` es una **referencia débil por UUID** al usuario en `auth_db`. No existe foreign key cruzada entre bases de datos; la integridad se garantiza a nivel de aplicación.

---

## Base de Datos: `auth_db` — ms-auth

```
┌────────────────────────────┐         ┌──────────────────────────────┐
│          users             │         │            roles              │
├────────────────────────────┤         ├──────────────────────────────┤
│ PK id              UUID    │         │ PK id              UUID       │
│    email           VARCHAR │         │    nombre          VARCHAR    │
│    password_hash   VARCHAR │         │    descripcion     TEXT       │
│    nombre_completo VARCHAR │         └──────────────────────────────┘
│    activo          BOOLEAN │                         △
│    creado_en       TIMESTAMPTZ                       │
└────────────────────────────┘                        │
              △                          ┌────────────────────────────┐
              │                          │         user_roles          │
              │                          ├────────────────────────────┤
              └──────────────────────────│ FK user_id         UUID    │
                                         │ FK role_id         UUID    │
                                         └────────────────────────────┘
```

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
│    evaluado_por_id     UUID  [ref. débil a auth_db] │
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

### `auth_db` — ejecutar en el PostgreSQL de ms-auth

```sql
-- ================================================================
-- EXTENSIONES
-- ================================================================
CREATE EXTENSION IF NOT EXISTS "pgcrypto";  -- para gen_random_uuid()

-- ================================================================
-- TIPOS ENUMERADOS
-- ================================================================
CREATE TYPE nombre_rol AS ENUM ('ADMIN', 'ANALYST', 'VIEWER');

-- ================================================================
-- TABLA: roles
-- ================================================================
CREATE TABLE roles (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre      nombre_rol  NOT NULL UNIQUE,
    descripcion TEXT
);

-- Datos iniciales
INSERT INTO roles (nombre, descripcion) VALUES
    ('ADMIN',   'Administrador del sistema: gestiona usuarios y puede evaluar'),
    ('ANALYST', 'Analista de crédito: puede crear y ver evaluaciones'),
    ('VIEWER',  'Observador: solo puede ver evaluaciones, sin crear');

-- ================================================================
-- TABLA: users
-- ================================================================
CREATE TABLE users (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255)    NOT NULL UNIQUE,
    password_hash   VARCHAR(255)    NOT NULL,
    nombre_completo VARCHAR(100)    NOT NULL,
    activo          BOOLEAN         NOT NULL DEFAULT TRUE,
    creado_en       TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- ================================================================
-- TABLA: user_roles (many-to-many, en práctica un usuario tiene 1 rol)
-- ================================================================
CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);

-- ================================================================
-- ÍNDICES
-- ================================================================
CREATE UNIQUE INDEX idx_users_email ON users (email);

-- ================================================================
-- USUARIO ADMIN INICIAL (hash de "Admin123!")
-- ================================================================
INSERT INTO users (email, password_hash, nombre_completo)
VALUES (
    'admin@banco.com',
    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewzEk2LBVJbS1Dm.',
    'Administrador del Sistema'
);

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u, roles r
WHERE u.email = 'admin@banco.com' AND r.nombre = 'ADMIN';
```

---

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
    evaluado_por_id      UUID           -- referencia débil a auth_db.users, sin FK cruzada
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

### Tablas en `auth_db` (ms-auth)

#### `users`
| Columna | Tipo | Descripción |
|---------|------|-------------|
| `id` | UUID | PK generado automáticamente |
| `email` | VARCHAR(255) | Email único, usado como username |
| `password_hash` | VARCHAR(255) | Hash bcrypt (factor 12) |
| `nombre_completo` | VARCHAR(100) | Nombre para mostrar |
| `activo` | BOOLEAN | Permite desactivar usuarios sin borrarlos |
| `creado_en` | TIMESTAMPTZ | Timestamp de creación con zona horaria |

#### `roles`
| Columna | Tipo | Descripción |
|---------|------|-------------|
| `id` | UUID | PK |
| `nombre` | ENUM | ADMIN / ANALYST / VIEWER |
| `descripcion` | TEXT | Descripción del rol para UI |

#### `user_roles`
Tabla de unión many-to-many entre `users` y `roles`. En la práctica del sistema actual, un usuario tiene exactamente un rol, pero el esquema permite expansión futura.

---

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
| `evaluado_por_id` | UUID (ref. débil) | ID del analista en `auth_db` — sin FK cruzada |

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

### ms-auth — `auth_db`

```properties
# ── Datasource ───────────────────────────────────────────────
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=${AUTH_DB_USERNAME:postgres}
quarkus.datasource.password=${AUTH_DB_PASSWORD:postgres}
quarkus.datasource.jdbc.url=jdbc:postgresql://${AUTH_DB_HOST:localhost}:5433/auth_db

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
