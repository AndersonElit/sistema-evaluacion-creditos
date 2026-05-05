package com.msrisk.model.port;

import com.msrisk.model.entity.RiskProfile;
import io.smallrye.mutiny.Uni;

public interface RiskPort {
    Uni<Integer> getScore(String cedula);
    Uni<RiskProfile> getProfile(String cedula);
}
