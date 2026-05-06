package com.mscreditevaluation.restapi.exception;

import com.mscreditevaluation.model.port.RiskServiceUnavailableException;
import com.mscreditevaluation.usecases.exception.EvaluacionNotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.stream.Collectors;

@Provider
@ApplicationScoped
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionMapper.class);

    @Override
    public Response toResponse(Exception ex) {
        if (ex instanceof WebApplicationException wae) {
            int status = wae.getResponse().getStatus();
            if (status == 401) {
                log.warn("Acceso no autenticado — 401 error={}", ex.getMessage());
            } else if (status == 403) {
                log.warn("Acceso no autorizado — 403 error={}", ex.getMessage());
            }
            return wae.getResponse();
        }
        if (ex instanceof ConstraintViolationException cve) {
            var detalles = cve.getConstraintViolations().stream()
                    .map(v -> new ErrorDetail(v.getPropertyPath().toString(), v.getMessage()))
                    .collect(Collectors.toList());
            String campos = detalles.stream()
                    .map(d -> d.campo() + ": " + d.mensaje())
                    .collect(Collectors.joining(", "));
            log.warn("Validación fallida — 422 campos={}", campos);
            return Response.status(422).entity(new ErrorResponse(422, "Validation Error", detalles)).build();
        }
        if (ex instanceof IllegalArgumentException iae) {
            log.warn("Argumento inválido — 422 error={}", iae.getMessage());
            return Response.status(422).entity(new ErrorResponse(422, iae.getMessage(), null)).build();
        }
        if (ex instanceof EvaluacionNotFoundException) {
            log.warn("Evaluación no encontrada — 404 error={}", ex.getMessage());
            return Response.status(404).entity(new ErrorResponse(404, ex.getMessage(), null)).build();
        }
        if (ex instanceof RiskServiceUnavailableException) {
            log.warn("Servicio de riesgos no disponible — 503 error={}", ex.getMessage());
            return Response.status(503).entity(
                    new ErrorResponse(503, "El servicio de riesgos no está disponible. Intente más tarde.", null)).build();
        }
        log.error("Error no manejado — 500 error={}", ex.getMessage(), ex);
        return Response.status(500).entity(new ErrorResponse(500, "Error interno", null)).build();
    }

    record ErrorDetail(String campo, String mensaje) {}
    record ErrorResponse(int status, String error, Object detalles) {
        public Instant timestamp() { return Instant.now(); }
    }
}
