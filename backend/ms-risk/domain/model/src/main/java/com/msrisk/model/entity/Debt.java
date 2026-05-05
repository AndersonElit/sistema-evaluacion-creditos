package com.msrisk.model.entity;

import java.math.BigDecimal;
import java.util.UUID;

public record Debt(UUID id, String description, BigDecimal monthlyPayment) {}
