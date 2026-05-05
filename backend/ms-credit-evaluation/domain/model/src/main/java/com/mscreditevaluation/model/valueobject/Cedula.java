package com.mscreditevaluation.model.valueobject;

public record Cedula(String valor) {

    public Cedula {
        if (valor == null || !valor.matches("\\d{10}")) {
            throw new IllegalArgumentException("Cédula debe tener 10 dígitos numéricos");
        }
        int provincia = Integer.parseInt(valor.substring(0, 2));
        if (provincia < 1 || provincia > 24) {
            throw new IllegalArgumentException("Código de provincia inválido: " + provincia);
        }
        if (!pasaModulo10(valor)) {
            throw new IllegalArgumentException("Cédula inválida: falla Módulo 10");
        }
    }

    private static boolean pasaModulo10(String cedula) {
        int[] coeficientes = {2, 1, 2, 1, 2, 1, 2, 1, 2};
        int suma = 0;
        for (int i = 0; i < 9; i++) {
            int val = Character.getNumericValue(cedula.charAt(i)) * coeficientes[i];
            suma += val >= 10 ? val - 9 : val;
        }
        int digitoVerificador = Character.getNumericValue(cedula.charAt(9));
        int esperado = suma % 10 == 0 ? 0 : 10 - (suma % 10);
        return esperado == digitoVerificador;
    }

    @Override
    public String toString() {
        return valor;
    }
}
