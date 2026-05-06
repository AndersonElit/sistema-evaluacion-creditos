package com.msrisk.restapi.resource;

import com.msrisk.restapi.dto.DebtDto;
import com.msrisk.restapi.dto.DeudasResponse;
import com.msrisk.restapi.dto.ScoreResponse;
import com.msrisk.usecases.GetRiskProfileUseCase;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

@Path("/v1/risk")
@Produces(MediaType.APPLICATION_JSON)
public class RiskResource {

    private static final Logger log = LoggerFactory.getLogger(RiskResource.class);

    @Inject
    GetRiskProfileUseCase getRiskProfileUseCase;

    @GET
    @Path("/score/{cedula}")
    public Uni<Response> getScore(@PathParam("cedula") String cedula) {
        if (!cedula.matches("\\d{10}")) {
            log.warn("Formato de cédula inválido en /score cedula='{}'", cedula);
            return Uni.createFrom().item(
                Response.status(400)
                    .entity("{\"error\":\"Formato de cédula inválido\"}")
                    .build());
        }
        String cedulaMask = maskCedula(cedula);
        log.debug("→ GET /v1/risk/score cedula={}", cedulaMask);

        return getRiskProfileUseCase.getScore(cedula)
                .invoke(score -> log.debug("← score={} cedula={}", score, cedulaMask))
                .map(score -> Response.ok(new ScoreResponse(cedula, score, Instant.now())).build());
    }

    @GET
    @Path("/debts/{cedula}")
    public Uni<Response> getDebts(@PathParam("cedula") String cedula) {
        if (!cedula.matches("\\d{10}")) {
            log.warn("Formato de cédula inválido en /debts cedula='{}'", cedula);
            return Uni.createFrom().item(
                Response.status(400)
                    .entity("{\"error\":\"Formato de cédula inválido\"}")
                    .build());
        }
        String cedulaMask = maskCedula(cedula);
        log.debug("→ GET /v1/risk/debts cedula={}", cedulaMask);

        return getRiskProfileUseCase.getProfile(cedula)
                .invoke(profile -> log.debug("← deudas={} totalMensual={} cedula={}",
                        profile.debts().size(), profile.totalMonthlyDebt(), cedulaMask))
                .map(profile -> {
                    var dtos = profile.debts().stream()
                            .map(d -> new DebtDto(d.id(), d.description(), d.monthlyPayment()))
                            .toList();
                    return Response.ok(new DeudasResponse(
                            cedula, dtos, profile.totalMonthlyDebt(), Instant.now())).build();
                });
    }

    private static String maskCedula(String cedula) {
        if (cedula == null || cedula.length() != 10) return "[cédula-inválida]";
        return cedula.substring(0, 2) + "******" + cedula.substring(8);
    }
}
