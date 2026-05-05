package com.msrisk;

import com.msrisk.postgres.repository.MockRiskAdapter;
import com.msrisk.usecases.GetRiskProfileUseCase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class BeanConfig {

    @Inject
    MockRiskAdapter mockRiskAdapter;

    @Produces
    @ApplicationScoped
    public GetRiskProfileUseCase getRiskProfileUseCase() {
        return new GetRiskProfileUseCase(mockRiskAdapter);
    }
}
