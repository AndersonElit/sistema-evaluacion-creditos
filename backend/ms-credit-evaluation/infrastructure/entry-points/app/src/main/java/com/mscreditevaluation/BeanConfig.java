package com.mscreditevaluation;

import com.mscreditevaluation.model.port.EvaluacionCreditoRepository;
import com.mscreditevaluation.model.port.NotificationPort;
import com.mscreditevaluation.model.port.RiskServicePort;
import com.mscreditevaluation.usecases.EvaluarCreditoUseCase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class BeanConfig {

    @Inject EvaluacionCreditoRepository repository;
    @Inject RiskServicePort riskService;
    @Inject NotificationPort notificationPort;

    @Produces
    @ApplicationScoped
    public EvaluarCreditoUseCase evaluarCreditoUseCase() {
        return new EvaluarCreditoUseCase(repository, riskService, notificationPort);
    }
}
