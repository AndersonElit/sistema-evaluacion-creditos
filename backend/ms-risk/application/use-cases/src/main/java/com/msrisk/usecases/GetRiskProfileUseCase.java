package com.msrisk.usecases;

import com.msrisk.model.entity.RiskProfile;
import com.msrisk.model.port.RiskPort;
import io.smallrye.mutiny.Uni;

public class GetRiskProfileUseCase {

    private final RiskPort riskPort;

    public GetRiskProfileUseCase(RiskPort riskPort) {
        this.riskPort = riskPort;
    }

    public Uni<Integer> getScore(String cedula) {
        return riskPort.getScore(cedula);
    }

    public Uni<RiskProfile> getProfile(String cedula) {
        return riskPort.getProfile(cedula);
    }
}
