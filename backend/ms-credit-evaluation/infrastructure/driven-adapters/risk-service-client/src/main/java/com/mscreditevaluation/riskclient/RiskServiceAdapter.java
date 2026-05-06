package com.mscreditevaluation.riskclient;

import com.mscreditevaluation.model.port.RiskServicePort;
import com.mscreditevaluation.model.port.RiskServiceUnavailableException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.math.BigDecimal;

@ApplicationScoped
public class RiskServiceAdapter implements RiskServicePort {

    @RestClient
    RiskServiceClient riskClient;

    @Override
    public Uni<RiskData> consultarRiesgo(String cedula) {
        return Uni.combine().all()
                .unis(riskClient.getScore(cedula), riskClient.getDebts(cedula))
                .asTuple()
                .map(tuple -> {
                    int score = tuple.getItem1().score();
                    BigDecimal deudaTotal = tuple.getItem2().totalMensual() != null
                            ? tuple.getItem2().totalMensual()
                            : BigDecimal.ZERO;
                    return new RiskData(score, deudaTotal);
                })
                .onFailure().transform(e ->
                        new RiskServiceUnavailableException(
                                "No se pudo consultar el servicio de riesgos", e));
    }
}
