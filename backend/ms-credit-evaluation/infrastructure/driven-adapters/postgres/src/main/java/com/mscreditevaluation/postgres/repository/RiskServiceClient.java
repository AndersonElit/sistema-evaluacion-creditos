package com.mscreditevaluation.postgres.repository;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RegisterRestClient(configKey = "risk-service")
@Path("/v1/risk")
@Produces(MediaType.APPLICATION_JSON)
public interface RiskServiceClient {

    record ScoreResponse(String cedula, int score, Instant timestamp) {}
    record DebtDto(String id, String descripcion, BigDecimal mensualidad) {}
    record DeudasResponse(String cedula, List<DebtDto> deudas,
                          BigDecimal totalMensual, Instant timestamp) {}

    @GET
    @Path("/score/{cedula}")
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10000)
    Uni<ScoreResponse> getScore(@PathParam("cedula") String cedula);

    @GET
    @Path("/debts/{cedula}")
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10000)
    Uni<DeudasResponse> getDebts(@PathParam("cedula") String cedula);
}
