package com.mscreditevaluation.restapi.exception;

import com.mscreditevaluation.model.port.RiskServiceUnavailableException;
import com.mscreditevaluation.usecases.exception.EvaluacionNotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.stream.Collectors;

@Provider
@ApplicationScoped
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger log = Logger.getLogger(GlobalExceptionMapper.class);

    @Override
    public Response toResponse(Exception ex) {
        if (ex instanceof WebApplicationException wae) {
            return wae.getResponse();
        }
        if (ex instanceof ConstraintViolationException cve) {
            var detalles = cve.getConstraintViolations().stream()
                    .map(v -> new ErrorDetail(v.getPropertyPath().toString(), v.getMessage()))
                    .collect(Collectors.toList());
            return Response.status(422).entity(new ErrorResponse(422, "Validation Error", detalles)).build();
        }
        if (ex instanceof IllegalArgumentException iae) {
            return Response.status(422).entity(new ErrorResponse(422, iae.getMessage(), null)).build();
        }
        if (ex instanceof EvaluacionNotFoundException) {
            return Response.status(404).entity(new ErrorResponse(404, ex.getMessage(), null)).build();
        }
        if (ex instanceof RiskServiceUnavailableException) {
            return Response.status(503).entity(
                    new ErrorResponse(503, "El servicio de riesgos no está disponible. Intente más tarde.", null)).build();
        }
        log.errorf(ex, "Error no manejado: %s", ex.getMessage());
        return Response.status(500).entity(new ErrorResponse(500, "Error interno", null)).build();
    }

    record ErrorDetail(String campo, String mensaje) {}
    record ErrorResponse(int status, String error, Object detalles) {
        public Instant timestamp() { return Instant.now(); }
    }
}
