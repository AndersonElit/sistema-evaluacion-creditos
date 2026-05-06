CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TYPE tipo_notif   AS ENUM ('APROBADO', 'RECHAZADO');
CREATE TYPE estado_notif AS ENUM ('PENDIENTE', 'ENVIADO', 'FALLIDO');

CREATE TABLE notifications (
    id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluacion_id        UUID          NOT NULL,
    destinatario_email   VARCHAR(255)  NOT NULL,
    tipo_notificacion    tipo_notif    NOT NULL,
    estado               estado_notif  NOT NULL DEFAULT 'PENDIENTE',
    intentos             INTEGER       NOT NULL DEFAULT 0,
    enviado_en           TIMESTAMPTZ,
    mensaje_sqs_id       VARCHAR(255),
    creado_en            TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_estado
    ON notifications (estado)
    WHERE estado = 'PENDIENTE';

-- Garantía de idempotencia: una sola notificación por evaluación
CREATE UNIQUE INDEX idx_notifications_evaluacion_unique
    ON notifications (evaluacion_id);
