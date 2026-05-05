package com.mscreditevaluation.usecases.command;

import java.math.BigDecimal;
import java.util.UUID;

public record SolicitudCreditoCommand(
    String cedula,
    BigDecimal montoSolicitado,
    int plazoAnios,
    BigDecimal salario,
    UUID evaluadoPorId,
    String destinatarioEmail
) {}
