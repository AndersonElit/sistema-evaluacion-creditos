package com.mscreditevaluation.model.entity;

import com.mscreditevaluation.model.valueobject.Cedula;
import com.mscreditevaluation.model.valueobject.Dinero;
import com.mscreditevaluation.model.valueobject.ScoreRiesgo;
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
    private final UUID evaluadoPorId;

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
