package com.msnotifications.postgres.entity;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class NotificationEntity extends PanacheEntityBase {

    @Id
    public UUID id = UUID.randomUUID();

    @Column(name = "evaluacion_id", nullable = false)
    public UUID evaluacionId;

    @Column(name = "destinatario_email", nullable = false)
    public String destinatarioEmail;

    @Column(name = "tipo_notificacion", nullable = false)
    @Enumerated(EnumType.STRING)
    public TipoNotif tipoNotificacion;

    @Column(name = "estado", nullable = false)
    @Enumerated(EnumType.STRING)
    public EstadoNotif estado = EstadoNotif.PENDIENTE;

    @Column(name = "intentos", nullable = false)
    public int intentos = 0;

    @Column(name = "enviado_en")
    public Instant enviadoEn;

    @Column(name = "mensaje_sqs_id")
    public String mensajeSqsId;

    @Column(name = "creado_en", nullable = false)
    public Instant creadoEn = Instant.now();

    public enum TipoNotif { APROBADO, RECHAZADO }
    public enum EstadoNotif { PENDIENTE, ENVIADO, FALLIDO }
}
