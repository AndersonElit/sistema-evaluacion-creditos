package com.mscreditevaluation.restapi.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record SolicitudCreditoRequest(

    @NotBlank(message = "La cédula es requerida")
    @Pattern(regexp = "\\d{10}", message = "La cédula debe tener exactamente 10 dígitos numéricos")
    String cedula,

    @NotNull(message = "El monto solicitado es requerido")
    @DecimalMin(value = "0.01", message = "El monto debe ser positivo")
    BigDecimal montoSolicitado,

    @Min(value = 1, message = "El plazo mínimo es 1 año")
    @Max(value = 30, message = "El plazo máximo es 30 años")
    int plazoAnios,

    @NotNull(message = "El salario es requerido")
    @DecimalMin(value = "0.01", message = "El salario debe ser positivo")
    BigDecimal salario,

    @Email(message = "Email del solicitante inválido")
    String destinatarioEmail
) {}
