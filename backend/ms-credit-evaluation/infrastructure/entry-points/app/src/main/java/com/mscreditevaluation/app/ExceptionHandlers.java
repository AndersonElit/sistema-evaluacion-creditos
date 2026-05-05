package com.mscreditevaluation.app;

import com.mscreditevaluation.model.port.RiskServiceUnavailableException;
import com.mscreditevaluation.usecases.exception.EvaluacionNotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.time.Instant;
import java.util.stream.Collectors;

@ApplicationScoped
public class ExceptionHandlers {

    private static final Logger log = Logger.getLogger(ExceptionHandlers.class);

    @ServerExceptionMapper(priority = 1)
    public Response handleConstraintViolation(ConstraintViolationException cve) {
        var detalles = cve.getConstraintViolations().stream()
                .map(v -> new ErrorDetail(v.getPropertyPath().toString(), v.getMessage()))
                .collect(Collectors.toList());
        return Response.status(422).entity(new ErrorBody(422, "Validation Error", detalles)).build();
    }

    @ServerExceptionMapper(priority = 1)
    public Response handleIllegalArgument(IllegalArgumentException iae) {
        return Response.status(422).entity(new ErrorBody(422, iae.getMessage(), null)).build();
    }

    @ServerExceptionMapper(priority = 1)
    public Response handleNotFound(EvaluacionNotFoundException nfe) {
        return Response.status(404).entity(new ErrorBody(404, nfe.getMessage(), null)).build();
    }

    @ServerExceptionMapper(priority = 1)
    public Response handleRiskUnavailable(RiskServiceUnavailableException rse) {
        return Response.status(503).entity(
                new ErrorBody(503, "El servicio de riesgos no está disponible. Intente más tarde.", null)).build();
    }

    @ServerExceptionMapper(priority = 2)
    public Response handleWebApplication(WebApplicationException wae) {
        return wae.getResponse();
    }

    @ServerExceptionMapper(priority = 3)
    public Response handleGeneric(Exception ex) {
        log.errorf(ex, "Error no manejado: %s", ex.getMessage());
        return Response.status(500).entity(new ErrorBody(500, "Error interno", null)).build();
    }

    record ErrorDetail(String campo, String mensaje) {}
    record ErrorBody(int status, String error, Object detalles) {
        public Instant timestamp() { return Instant.now(); }
    }
}
