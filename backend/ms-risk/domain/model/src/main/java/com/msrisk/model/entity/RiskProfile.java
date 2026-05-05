package com.msrisk.model.entity;

import java.math.BigDecimal;
import java.util.List;

public record RiskProfile(String cedula, int score, List<Debt> debts) {

    public BigDecimal totalMonthlyDebt() {
        return debts.stream()
                .map(Debt::monthlyPayment)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
