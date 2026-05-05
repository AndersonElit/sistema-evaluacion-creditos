# Modelo de Base de Datos — PostgreSQL

## Diagrama Entidad-Relación (Texto)

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
│ FK evaluado_por_id     UUID                        │
│    notificacion_enviada BOOLEAN DEFAULT FALSE       │
└────────────────────────────────────────────────────┘
              △
              │ 1
              │
              │ 0..1
┌────────────────────────────────────────────────────┐
│                    notifications                    │
├────────────────────────────────────────────────────┤
│ PK id                  UUID                        │
│ FK evaluacion_id       UUID         NOT NULL        │
│    destinatario_email  VARCHAR(255) NOT NULL        │
│    tipo_notificacion   VARCHAR(20)  NOT NULL        │
│    estado              VARCHAR(20)  NOT NULL        │
│    intentos            INTEGER DEFAULT 0            │
│    enviado_en          TIMESTAMPTZ                  │
│    mensaje_sqs_id      VARCHAR(255)                 │
│    creado_en           TIMESTAMPTZ  NOT NULL        │
└────────────────────────────────────────────────────┘
```

---

## DDL — Scripts de Creación

```sql
-- ================================================================
-- EXTENSIONES
-- ================================================================
CREATE EXTENSION IF NOT EXISTS "pgcrypto";  -- para gen_random_uuid()

-- ================================================================
-- TIPOS ENUMERADOS
-- ================================================================
CREATE TYPE estado_evaluacion AS ENUM ('APROBADO', 'RECHAZADO', 'PENDIENTE');
CREATE TYPE nombre_rol        AS ENUM ('ADMIN', 'ANALYST', 'VIEWER');
CREATE TYPE tipo_notif        AS ENUM ('APROBADO', 'RECHAZADO');
CREATE TYPE estado_notif      AS ENUM ('PENDIENTE', 'ENVIADO', 'FALLIDO');

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
    evaluado_por_id      UUID           REFERENCES users(id) ON DELETE SET NULL,
    notificacion_enviada BOOLEAN        NOT NULL DEFAULT FALSE
);

-- ================================================================
-- TABLA: notifications
-- ================================================================
CREATE TABLE notifications (
    id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluacion_id        UUID          NOT NULL REFERENCES credit_evaluations(id) ON DELETE CASCADE,
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

-- Notificaciones pendientes (polling del worker)
CREATE INDEX idx_notifications_estado
    ON notifications (estado)
    WHERE estado = 'PENDIENTE';

-- Evitar notificaciones duplicadas por evaluación
CREATE UNIQUE INDEX idx_notifications_evaluacion_unique
    ON notifications (evaluacion_id);

-- Búsqueda rápida de usuarios por email
CREATE UNIQUE INDEX idx_users_email
    ON users (email);

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

## Descripción de Tablas

### `users`
| Columna | Tipo | Descripción |
|---------|------|-------------|
| `id` | UUID | PK generado automáticamente |
| `email` | VARCHAR(255) | Email único, usado como username |
| `password_hash` | VARCHAR(255) | Hash bcrypt (factor 12) |
| `nombre_completo` | VARCHAR(100) | Nombre para mostrar |
| `activo` | BOOLEAN | Permite desactivar usuarios sin borrarlos |
| `creado_en` | TIMESTAMPTZ | Timestamp de creación con zona horaria |

### `roles`
| Columna | Tipo | Descripción |
|---------|------|-------------|
| `id` | UUID | PK |
| `nombre` | ENUM | ADMIN / ANALYST / VIEWER |
| `descripcion` | TEXT | Descripción del rol para UI |

### `user_roles`
Tabla de unión many-to-many entre `users` y `roles`. En la práctica del sistema actual, un usuario tiene exactamente un rol, pero el esquema permite expansión futura.

### `credit_evaluations`
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
| `evaluado_por_id` | UUID FK | Analista que realizó la evaluación |
| `notificacion_enviada` | BOOLEAN | Flag para idempotencia de notificaciones |

### `notifications`
| Columna | Tipo | Descripción |
|---------|------|-------------|
| `id` | UUID | PK |
| `evaluacion_id` | UUID FK | Evaluación asociada (unique: 1 notif por eval) |
| `destinatario_email` | VARCHAR(255) | Email del solicitante |
| `tipo_notificacion` | ENUM | APROBADO / RECHAZADO |
| `estado` | ENUM | PENDIENTE / ENVIADO / FALLIDO |
| `intentos` | INTEGER | Contador de intentos de envío |
| `enviado_en` | TIMESTAMPTZ | Timestamp del envío exitoso |
| `mensaje_sqs_id` | VARCHAR(255) | Message ID de SQS para trazabilidad |
| `creado_en` | TIMESTAMPTZ | Timestamp de creación |

---

## Configuración Quarkus (application.properties)

```properties
# ── Datasource ───────────────────────────────────────────────
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=${DB_USERNAME:postgres}
quarkus.datasource.password=${DB_PASSWORD:postgres}
quarkus.datasource.jdbc.url=jdbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME:creditos_db}

# ── Hibernate ORM ────────────────────────────────────────────
# En producción: none o validate — NUNCA drop-and-create
quarkus.hibernate-orm.database.generation=validate

# ── Flyway (migraciones) ─────────────────────────────────────
quarkus.flyway.migrate-at-start=true
quarkus.flyway.locations=classpath:db/migration

# ── Seguridad SQL: usar siempre PreparedStatements (Panache lo hace automáticamente)
# Nunca concatenar strings en queries — usar parámetros con ?1, :param
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
