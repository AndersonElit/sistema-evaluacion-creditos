package com.mscreditevaluation.model.port;

import io.smallrye.mutiny.Uni;
import java.math.BigDecimal;

public interface RiskServicePort {
    record RiskData(int score, BigDecimal totalDeudaMensual) {}
    Uni<RiskData> consultarRiesgo(String cedula);
}
