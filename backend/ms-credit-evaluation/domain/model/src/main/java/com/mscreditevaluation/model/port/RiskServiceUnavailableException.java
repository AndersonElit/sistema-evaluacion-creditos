package com.mscreditevaluation.model.port;

public class RiskServiceUnavailableException extends RuntimeException {
    public RiskServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
