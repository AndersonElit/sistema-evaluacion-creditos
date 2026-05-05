package com.mscreditevaluation.restapi.dto;

import com.mscreditevaluation.model.entity.EvaluacionCredito;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record EvaluacionCreditoResponse(
    UUID id,
    String cedula,
    BigDecimal montoSolicitado,
    int plazoAnios,
    BigDecimal salario,
    int scoreRiesgo,
    BigDecimal deudaMensualTotal,
    String estadoFinal,
    Instant fechaEvaluacion,
    UUID evaluadoPorId
) {
    public static EvaluacionCreditoResponse from(EvaluacionCredito e) {
        return new EvaluacionCreditoResponse(
            e.getId(),
            e.getCedula().valor(),
            e.getMontoSolicitado().cantidad(),
            e.getPlazoAnios(),
            e.getSalario().cantidad(),
            e.getScoreRiesgo().valor(),
            e.getDeudaMensual().cantidad(),
            e.getEstadoFinal().name(),
            e.getFechaEvaluacion(),
            e.getEvaluadoPorId()
        );
    }
}
