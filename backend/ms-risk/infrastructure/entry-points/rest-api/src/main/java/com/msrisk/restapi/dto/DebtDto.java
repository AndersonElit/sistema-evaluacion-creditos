package com.msrisk.restapi.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DebtDto(UUID id, String descripcion, BigDecimal mensualidad) {}
