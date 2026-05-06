package com.msnotifications.sqsconsumer.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msnotifications.model.entity.EvaluacionCompletadaEvent;
import com.msnotifications.postgres.entity.NotificationEntity;
import com.msnotifications.postgres.repository.EmailSenderService;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    @Inject SqsAsyncClient sqsClient;
    @Inject EmailSenderService emailSender;
    @Inject ObjectMapper objectMapper;

    @ConfigProperty(name = "sqs.queue.url")
    String queueUrl;

    @Scheduled(every = "20s", delayed = "30s")
    public Uni<Void> procesarMensajes() {
        return Uni.createFrom().completionStage(() ->
                sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(5)
                        .build()))
                .chain(response -> {
                    var messages = response.messages();
                    if (messages.isEmpty()) return Uni.createFrom().voidItem();

                    log.info("Procesando {} mensajes de SQS", messages.size());

                    return Multi.createFrom().iterable(messages)
                            .onItem().transformToUniAndConcatenate(this::procesarYEliminar)
                            .collect().asList()
                            .replaceWithVoid();
                });
    }

    @WithTransaction
    Uni<Void> procesarYEliminar(Message msg) {
        return procesarEvento(msg)
                .chain(() -> eliminar(msg.receiptHandle()))
                .onFailure().invoke(e ->
                        log.error("Error procesando mensaje {}: {}", msg.messageId(), e.getMessage()))
                .onFailure().recoverWithNull();
    }

    private Uni<Void> procesarEvento(Message msg) {
        EvaluacionCompletadaEvent evento;
        try {
            evento = objectMapper.readValue(msg.body(), EvaluacionCompletadaEvent.class);
        } catch (Exception e) {
            return Uni.createFrom().failure(e);
        }

        UUID evalId = UUID.fromString(evento.evaluacionId());

        return NotificationEntity.existsByEvaluacionIdAndEstado(
                evalId, NotificationEntity.EstadoNotif.ENVIADO)
                .chain(yaEnviado -> {
                    if (yaEnviado) {
                        log.info("Notificación ya enviada para evaluacion {} — ignorando", evalId);
                        return Uni.createFrom().voidItem();
                    }

                    NotificationEntity notif = new NotificationEntity();
                    notif.evaluacionId = evalId;
                    notif.destinatarioEmail = evento.destinatarioEmail();
                    notif.tipoNotificacion = NotificationEntity.TipoNotif.valueOf(evento.estadoFinal());
                    notif.mensajeSqsId = msg.messageId();

                    return notif.<NotificationEntity>persist()
                            .chain(n -> emailSender.enviar(
                                    evento.destinatarioEmail(),
                                    evento.estadoFinal(),
                                    evento.montoSolicitado(),
                                    evento.fechaEvaluacion())
                                    .invoke(() -> {
                                        n.estado = NotificationEntity.EstadoNotif.ENVIADO;
                                        n.enviadoEn = Instant.now();
                                        n.intentos++;
                                    }));
                });
    }

    private Uni<Void> eliminar(String receiptHandle) {
        return Uni.createFrom().completionStage(() ->
                sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(receiptHandle)
                        .build()))
                .replaceWithVoid();
    }
}
