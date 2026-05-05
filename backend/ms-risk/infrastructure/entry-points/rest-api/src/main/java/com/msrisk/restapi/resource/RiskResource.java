package com.msrisk.restapi.resource;

import com.msrisk.model.port.RiskPort;
import com.msrisk.restapi.dto.DebtDto;
import com.msrisk.restapi.dto.DeudasResponse;
import com.msrisk.restapi.dto.ScoreResponse;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;

@Path("/v1/risk")
@Produces(MediaType.APPLICATION_JSON)
public class RiskResource {

    @Inject
    RiskPort riskPort;

    @GET
    @Path("/score/{cedula}")
    public Uni<Response> getScore(@PathParam("cedula") String cedula) {
        if (!cedula.matches("\\d{10}")) {
            return Uni.createFrom().item(
                Response.status(400)
                    .entity("{\"error\":\"Formato de cédula inválido\"}")
                    .build());
        }
        return riskPort.getScore(cedula)
                .map(score -> Response.ok(new ScoreResponse(cedula, score, Instant.now())).build());
    }

    @GET
    @Path("/debts/{cedula}")
    public Uni<Response> getDebts(@PathParam("cedula") String cedula) {
        if (!cedula.matches("\\d{10}")) {
            return Uni.createFrom().item(
                Response.status(400)
                    .entity("{\"error\":\"Formato de cédula inválido\"}")
                    .build());
        }
        return riskPort.getProfile(cedula)
                .map(profile -> {
                    var dtos = profile.debts().stream()
                            .map(d -> new DebtDto(d.id(), d.description(), d.monthlyPayment()))
                            .toList();
                    return Response.ok(new DeudasResponse(
                            cedula, dtos, profile.totalMonthlyDebt(), Instant.now())).build();
                });
    }
}
