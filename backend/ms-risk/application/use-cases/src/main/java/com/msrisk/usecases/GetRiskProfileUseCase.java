package com.msrisk.usecases;

import com.msrisk.model.entity.RiskProfile;
import com.msrisk.model.port.RiskPort;
import io.smallrye.mutiny.Uni;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetRiskProfileUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetRiskProfileUseCase.class);

    private final RiskPort riskPort;

    public GetRiskProfileUseCase(RiskPort riskPort) {
        this.riskPort = riskPort;
    }

    public Uni<Integer> getScore(String cedula) {
        String cedulaMask = maskCedula(cedula);
        log.debug("Consultando score cedula={}", cedulaMask);
        return riskPort.getScore(cedula)
                .invoke(score -> log.debug("Score generado cedula={} score={}", cedulaMask, score));
    }

    public Uni<RiskProfile> getProfile(String cedula) {
        String cedulaMask = maskCedula(cedula);
        log.debug("Consultando perfil de riesgo cedula={}", cedulaMask);
        return riskPort.getProfile(cedula)
                .invoke(profile -> log.debug("Perfil generado cedula={} deudas={} totalMensual={}",
                        cedulaMask, profile.debts().size(), profile.totalMonthlyDebt()));
    }

    private static String maskCedula(String cedula) {
        if (cedula == null || cedula.length() != 10) return "[cédula-inválida]";
        return cedula.substring(0, 2) + "******" + cedula.substring(8);
    }
}
