package com.msnotifications.ses.adapter;

import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ses.SesAsyncClient;
import software.amazon.awssdk.services.ses.model.*;

import java.math.BigDecimal;
import java.util.Locale;

@ApplicationScoped
public class EmailSenderService {

    private static final Logger log = LoggerFactory.getLogger(EmailSenderService.class);

    @Inject
    SesAsyncClient sesClient;

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "aws.ses.from.email", defaultValue = "noreply@banco.com")
    String fromEmail;

    public Uni<Void> enviar(String destinatario, String estadoFinal,
                             BigDecimal monto, String fecha) {
        String destinatarioMask = maskEmail(destinatario);
        log.debug("Enviando email via SES destinatario={} tipo={}", destinatarioMask, estadoFinal);

        String asunto = "APROBADO".equals(estadoFinal)
                ? "Su solicitud de crédito fue APROBADA ✓"
                : "Su solicitud de crédito fue RECHAZADA";
        String cuerpoHtml = generarPlantilla(estadoFinal, monto, fecha);
        var ctx = vertx.getOrCreateContext();

        return Uni.createFrom().completionStage(() ->
                sesClient.sendEmail(SendEmailRequest.builder()
                        .destination(d -> d.toAddresses(destinatario))
                        .message(m -> m
                                .subject(c -> c.data(asunto).charset("UTF-8"))
                                .body(b -> b.html(c -> c.data(cuerpoHtml).charset("UTF-8")))
                        )
                        .source(fromEmail)
                        .build()))
                .emitOn(cmd -> ctx.runOnContext(v -> cmd.run()))
                .invoke(r -> log.info("Email enviado via SES destinatario={} tipo={}", destinatarioMask, estadoFinal))
                .replaceWithVoid()
                .onFailure().invoke(e -> log.error("Error enviando email SES destinatario={} tipo={} error={}",
                        destinatarioMask, estadoFinal, e.getMessage(), e));
    }

    private String generarPlantilla(String estado, BigDecimal monto, String fecha) {
        if ("APROBADO".equals(estado)) {
            return String.format(Locale.US, """
                <html><body style="font-family:Arial,sans-serif;padding:20px">
                  <h2 style="color:#27ae60">Felicitaciones — Su crédito fue APROBADO</h2>
                  <p>Su solicitud por <strong>$%,.2f USD</strong> ha sido aprobada.</p>
                  <p>Fecha: <strong>%s</strong></p>
                  <p>Un asesor se comunicará con usted para continuar el proceso.</p>
                  <hr><p style="color:#888;font-size:12px">Mensaje automático. No responder.</p>
                </body></html>
                """, monto, fecha);
        }
        return String.format(Locale.US, """
            <html><body style="font-family:Arial,sans-serif;padding:20px">
              <h2 style="color:#e74c3c">Su solicitud de crédito fue RECHAZADA</h2>
              <p>Lamentablemente su solicitud por <strong>$%,.2f USD</strong> no fue aprobada.</p>
              <p>Fecha: <strong>%s</strong></p>
              <p>Para más información comuníquese con su asesor.</p>
              <hr><p style="color:#888;font-size:12px">Mensaje automático. No responder.</p>
            </body></html>
            """, monto, fecha);
    }

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "[email-inválido]";
        int at = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(at);
    }
}
