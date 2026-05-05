package com.mscreditevaluation.model.entity;

import com.mscreditevaluation.model.valueobject.Cedula;
import com.mscreditevaluation.model.valueobject.Dinero;
import com.mscreditevaluation.model.valueobject.ScoreRiesgo;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class EvaluacionCreditoTest {

    private static final Dinero SALARIO_2000  = Dinero.usd(new BigDecimal("2000.00"));
    private static final Dinero MONTO_5000    = Dinero.usd(new BigDecimal("5000.00"));
    private static final Dinero DEUDA_200     = Dinero.usd(new BigDecimal("200.00"));
    private static final Dinero DEUDA_MINIMA  = Dinero.usd(new BigDecimal("0.01"));

    @Test
    void aprobado_score_alto_deuda_baja() {
        var estado = EvaluacionCredito.evaluar(
                new ScoreRiesgo(85), DEUDA_200, SALARIO_2000, MONTO_5000, 3);
        assertThat(estado).isEqualTo(EstadoEvaluacion.APROBADO);
    }

    @Test
    void rechazado_score_igual_70_limite_estricto() {
        var estado = EvaluacionCredito.evaluar(
                new ScoreRiesgo(70), DEUDA_200, SALARIO_2000, MONTO_5000, 3);
        assertThat(estado).isEqualTo(EstadoEvaluacion.RECHAZADO);
    }

    @Test
    void rechazado_score_menor_70() {
        var estado = EvaluacionCredito.evaluar(
                new ScoreRiesgo(45), DEUDA_200, SALARIO_2000, MONTO_5000, 3);
        assertThat(estado).isEqualTo(EstadoEvaluacion.RECHAZADO);
    }

    @Test
    void rechazado_carga_supera_40_porciento_aunque_score_sea_alto() {
        var salarioBajo = Dinero.usd(new BigDecimal("1000.00"));
        var montoAlto   = Dinero.usd(new BigDecimal("10000.00"));
        var deudaAlta   = Dinero.usd(new BigDecimal("300.00"));
        var estado = EvaluacionCredito.evaluar(
                new ScoreRiesgo(90), deudaAlta, salarioBajo, montoAlto, 2);
        assertThat(estado).isEqualTo(EstadoEvaluacion.RECHAZADO);
    }

    @Test
    void aprobado_en_limite_exacto_score_71_deuda_minima() {
        var monto1000 = Dinero.usd(new BigDecimal("1000.00"));
        var estado = EvaluacionCredito.evaluar(
                new ScoreRiesgo(71), DEUDA_MINIMA, SALARIO_2000, monto1000, 1);
        assertThat(estado).isEqualTo(EstadoEvaluacion.APROBADO);
    }

    @Test
    void builder_genera_id_unico_por_instancia() {
        var e1 = EvaluacionCredito.builder()
                .cedula(new Cedula("1713175071")).montoSolicitado(MONTO_5000)
                .plazoAnios(3).salario(SALARIO_2000)
                .scoreRiesgo(new ScoreRiesgo(85)).deudaMensual(DEUDA_200)
                .estadoFinal(EstadoEvaluacion.APROBADO).build();
        var e2 = EvaluacionCredito.builder()
                .cedula(new Cedula("1713175071")).montoSolicitado(MONTO_5000)
                .plazoAnios(3).salario(SALARIO_2000)
                .scoreRiesgo(new ScoreRiesgo(85)).deudaMensual(DEUDA_200)
                .estadoFinal(EstadoEvaluacion.APROBADO).build();

        assertThat(e1.getId()).isNotEqualTo(e2.getId());
    }

    @Test
    void builder_asigna_fecha_de_evaluacion_automaticamente() {
        var e = EvaluacionCredito.builder()
                .cedula(new Cedula("1713175071")).montoSolicitado(MONTO_5000)
                .plazoAnios(3).salario(SALARIO_2000)
                .scoreRiesgo(new ScoreRiesgo(80)).deudaMensual(DEUDA_200)
                .estadoFinal(EstadoEvaluacion.APROBADO).build();
        assertThat(e.getFechaEvaluacion()).isNotNull();
    }
}
