package com.mscreditevaluation.model.valueobject;

import java.math.BigDecimal;

public record Dinero(BigDecimal cantidad, String moneda) {

    public static final String USD = "USD";

    public Dinero {
        if (cantidad == null || cantidad.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("El monto debe ser positivo");
        }
        if (moneda == null || moneda.isBlank()) {
            throw new IllegalArgumentException("La moneda es requerida");
        }
    }

    public static Dinero usd(BigDecimal cantidad) {
        return new Dinero(cantidad, USD);
    }

    public Dinero sumar(Dinero otro) {
        return new Dinero(this.cantidad.add(otro.cantidad), this.moneda);
    }

    public boolean menorQue(Dinero otro) {
        return this.cantidad.compareTo(otro.cantidad) < 0;
    }
}
