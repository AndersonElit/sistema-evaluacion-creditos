package com.msrisk.postgres.repository;

import com.msrisk.model.entity.Debt;
import com.msrisk.model.entity.RiskProfile;
import com.msrisk.model.port.RiskPort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class MockRiskAdapter implements RiskPort {

    private static final Logger log = LoggerFactory.getLogger(MockRiskAdapter.class);

    @Override
    public Uni<Integer> getScore(String cedula) {
        String cedulaMask = maskCedula(cedula);
        log.debug("Generando score simulado cedula={} latencia=2000ms", cedulaMask);
        return Uni.createFrom().item(() -> {
            int score = ThreadLocalRandom.current().nextInt(0, 101);
            log.debug("Score simulado generado cedula={} score={}", cedulaMask, score);
            return score;
        }).onItem().delayIt().by(Duration.ofMillis(2000));
    }

    @Override
    public Uni<RiskProfile> getProfile(String cedula) {
        String cedulaMask = maskCedula(cedula);
        log.debug("Generando perfil de riesgo simulado cedula={} latencia=1500ms", cedulaMask);
        return Uni.createFrom().item(() -> {
            int debtCount = ThreadLocalRandom.current().nextInt(0, 6);
            List<Debt> debts = new ArrayList<>();
            for (int i = 0; i < debtCount; i++) {
                BigDecimal monthly = BigDecimal.valueOf(
                        ThreadLocalRandom.current().nextDouble(50, 500));
                debts.add(new Debt(UUID.randomUUID(), "Deuda " + (i + 1), monthly));
            }
            int score = ThreadLocalRandom.current().nextInt(0, 101);
            log.debug("Perfil simulado generado cedula={} score={} deudas={}", cedulaMask, score, debtCount);
            return new RiskProfile(cedula, score, debts);
        }).onItem().delayIt().by(Duration.ofMillis(1500));
    }

    private static String maskCedula(String cedula) {
        if (cedula == null || cedula.length() != 10) return "[cédula-inválida]";
        return cedula.substring(0, 2) + "******" + cedula.substring(8);
    }
}
