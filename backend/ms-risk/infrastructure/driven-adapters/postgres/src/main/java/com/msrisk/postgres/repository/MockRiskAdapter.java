package com.msrisk.postgres.repository;

import com.msrisk.model.entity.Debt;
import com.msrisk.model.entity.RiskProfile;
import com.msrisk.model.port.RiskPort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@ApplicationScoped
public class MockRiskAdapter implements RiskPort {

    @Override
    public Uni<Integer> getScore(String cedula) {
        return Uni.createFrom().item(() -> ThreadLocalRandom.current().nextInt(0, 101))
                .onItem().delayIt().by(Duration.ofMillis(2000));
    }

    @Override
    public Uni<RiskProfile> getProfile(String cedula) {
        return Uni.createFrom().item(() -> {
            int debtCount = ThreadLocalRandom.current().nextInt(0, 6);
            List<Debt> debts = new ArrayList<>();
            for (int i = 0; i < debtCount; i++) {
                BigDecimal monthly = BigDecimal.valueOf(
                        ThreadLocalRandom.current().nextDouble(50, 500));
                debts.add(new Debt(UUID.randomUUID(), "Deuda " + (i + 1), monthly));
            }
            int score = ThreadLocalRandom.current().nextInt(0, 101);
            return new RiskProfile(cedula, score, debts);
        }).onItem().delayIt().by(Duration.ofMillis(1500));
    }
}
