package com.msnotifications.usecase;

import com.msnotifications.model.entity.EvaluacionCompletadaEvent;
import com.msnotifications.ses.adapter.EmailSenderService;
import com.msnotifications.model.port.NotificationPort;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@ApplicationScoped
public class ProcesarNotificacionUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcesarNotificacionUseCase.class);

    @Inject
    NotificationPort notificationPort;

    @Inject
    EmailSenderService emailSender;

    @WithTransaction
    public Uni<Void> procesar(EvaluacionCompletadaEvent evento, String mensajeSqsId) {
        UUID evalId = UUID.fromString(evento.evaluacionId());

        return notificationPort.existeEnviada(evalId)
                .chain(yaEnviado -> {
                    if (yaEnviado) {
                        log.info("Notificación ya enviada para evaluacion {} — ignorando", evalId);
                        return Uni.createFrom().voidItem();
                    }

                    return notificationPort.crear(
                                    evalId,
                                    evento.destinatarioEmail(),
                                    evento.estadoFinal(),
                                    mensajeSqsId)
                            .chain(notifId -> emailSender.enviar(
                                    evento.destinatarioEmail(),
                                    evento.estadoFinal(),
                                    evento.montoSolicitado(),
                                    evento.fechaEvaluacion())
                                    .chain(() -> notificationPort.marcarEnviada(notifId)));
                });
    }
}
