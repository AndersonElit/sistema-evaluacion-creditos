package com.mscreditevaluation.model.valueobject;

public record ScoreRiesgo(int valor) {

    public ScoreRiesgo {
        if (valor < 0 || valor > 100) {
            throw new IllegalArgumentException("Score debe estar entre 0 y 100");
        }
    }

    public boolean esSuficiente() {
        return valor > 70;
    }
}
