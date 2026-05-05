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
