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
