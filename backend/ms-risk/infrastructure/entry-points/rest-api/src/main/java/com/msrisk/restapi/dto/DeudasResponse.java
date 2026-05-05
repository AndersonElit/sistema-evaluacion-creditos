package com.msrisk.restapi.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DeudasResponse(String cedula, List<DebtDto> deudas,
                              BigDecimal totalMensual, Instant timestamp) {}
