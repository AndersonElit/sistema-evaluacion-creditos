package com.mscreditevaluation.riskclient;

import com.mscreditevaluation.model.port.RiskServicePort;
import com.mscreditevaluation.model.port.RiskServiceUnavailableException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

@ApplicationScoped
public class RiskServiceAdapter implements RiskServicePort {

    private static final Logger log = LoggerFactory.getLogger(RiskServiceAdapter.class);

    @RestClient
    RiskServiceClient riskClient;

    @Override
    public Uni<RiskData> consultarRiesgo(String cedula) {
        long inicio = System.currentTimeMillis();
        String cedulaMask = maskCedula(cedula);

        log.debug("Consultando ms-risk cedula={}", cedulaMask);

        return Uni.combine().all()
                .unis(riskClient.getScore(cedula), riskClient.getDebts(cedula))
                .asTuple()
                .map(tuple -> {
                    long elapsed = System.currentTimeMillis() - inicio;
                    int score = tuple.getItem1().score();
                    BigDecimal deudaTotal = tuple.getItem2().totalMensual() != null
                            ? tuple.getItem2().totalMensual()
                            : BigDecimal.ZERO;

                    if (elapsed > 2_500) {
                        log.warn("Respuesta lenta de ms-risk elapsed={}ms cedula={}", elapsed, cedulaMask);
                    } else {
                        log.debug("Respuesta ms-risk recibida cedula={} score={} deudaTotal={} elapsed={}ms",
                                cedulaMask, score, deudaTotal, elapsed);
                    }

                    return new RiskData(score, deudaTotal);
                })
                .onFailure().invoke(e -> log.error("Error consultando ms-risk cedula={} elapsed={}ms error={}",
                        cedulaMask, System.currentTimeMillis() - inicio, e.getMessage(), e))
                .onFailure().transform(e ->
                        new RiskServiceUnavailableException(
                                "No se pudo consultar el servicio de riesgos", e));
    }

    private static String maskCedula(String cedula) {
        if (cedula == null || cedula.length() != 10) return "[cédula-inválida]";
        return cedula.substring(0, 2) + "******" + cedula.substring(8);
    }
}
