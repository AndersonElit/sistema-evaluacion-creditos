package com.mscreditevaluation.restapi.resource;

import com.mscreditevaluation.restapi.dto.EvaluacionCreditoResponse;
import com.mscreditevaluation.restapi.dto.SolicitudCreditoRequest;
import com.mscreditevaluation.usecases.EvaluarCreditoUseCase;
import com.mscreditevaluation.usecases.command.SolicitudCreditoCommand;
import com.mscreditevaluation.usecases.util.LogMask;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.UUID;

@ApplicationScoped
@Path("/v1/credit-evaluations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CreditEvaluationResource {

    private static final Logger log = LoggerFactory.getLogger(CreditEvaluationResource.class);

    @Inject EvaluarCreditoUseCase useCase;
    @Inject JsonWebToken jwt;

    @POST
    @RolesAllowed({"ADMIN", "ANALYST"})
    public Uni<Response> evaluar(@Valid SolicitudCreditoRequest request) {
        long inicio = System.currentTimeMillis();
        UUID evaluadorId = UUID.fromString(jwt.getSubject());
        String email = jwt.getClaim("email");

        log.info("→ POST /v1/credit-evaluations cedula={} monto={} evaluadorId={}",
                LogMask.cedula(request.cedula()), LogMask.monto(request.montoSolicitado()), evaluadorId);

        var command = new SolicitudCreditoCommand(
            request.cedula(),
            request.montoSolicitado(),
            request.plazoAnios(),
            request.salario(),
            evaluadorId,
            request.destinatarioEmail() != null ? request.destinatarioEmail() : email
        );

        return Uni.createFrom().deferred(() -> useCase.ejecutar(command))
                .map(result -> {
                    var resp = EvaluacionCreditoResponse.from(result.evaluacion());
                    log.info("← 201 CREATED evaluacionId={} estado={} elapsed={}ms",
                            resp.id(), resp.estadoFinal(), System.currentTimeMillis() - inicio);
                    return Response.created(URI.create("/v1/credit-evaluations/" + resp.id()))
                            .entity(resp)
                            .build();
                });
    }

    @GET
    @Authenticated
    public Uni<Response> listar(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        log.debug("→ GET /v1/credit-evaluations page={} size={}", page, size);
        return useCase.listarTodas(page, size)
                .map(lista -> lista.stream()
                        .map(r -> EvaluacionCreditoResponse.from(r.evaluacion()))
                        .toList())
                .invoke(lista -> log.debug("← 200 OK resultados={}", lista.size()))
                .map(lista -> Response.ok(lista).build());
    }

    @GET
    @Path("/{id}")
    @Authenticated
    public Uni<Response> buscarPorId(@PathParam("id") UUID id) {
        log.debug("→ GET /v1/credit-evaluations/{}", id);
        return useCase.buscarPorId(id)
                .invoke(result -> log.debug("← 200 OK evaluacionId={}", id))
                .map(result -> Response.ok(EvaluacionCreditoResponse.from(result.evaluacion())).build());
    }
}
