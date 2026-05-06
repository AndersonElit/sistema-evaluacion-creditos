package com.mscreditevaluation.usecases.util;

import java.math.BigDecimal;

public final class LogMask {

    private LogMask() {}

    public static String cedula(String cedula) {
        if (cedula == null || cedula.length() != 10) return "[cédula-inválida]";
        return cedula.substring(0, 2) + "******" + cedula.substring(8);
    }

    public static String email(String email) {
        if (email == null || !email.contains("@")) return "[email-inválido]";
        int at = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(at);
    }

    public static String monto(BigDecimal monto) {
        if (monto == null) return "[null]";
        long miles = monto.longValue() / 1_000;
        return "[" + miles + "k-" + (miles + 10) + "k]";
    }
}
