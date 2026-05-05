package com.mscreditevaluation.usecases.exception;

import java.util.UUID;

public class EvaluacionNotFoundException extends RuntimeException {
    public EvaluacionNotFoundException(UUID id) {
        super("Evaluación no encontrada: " + id);
    }
}
