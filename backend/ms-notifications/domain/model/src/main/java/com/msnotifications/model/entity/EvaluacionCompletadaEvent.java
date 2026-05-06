package com.msnotifications.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluacionCompletadaEvent(
    String evaluacionId,
    String cedula,
    String destinatarioEmail,
    String nombreSolicitante,
    String estadoFinal,
    BigDecimal montoSolicitado,
    String moneda,
    int plazoAnios,
    String fechaEvaluacion,
    String version
) {}
