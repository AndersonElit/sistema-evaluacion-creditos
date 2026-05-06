package com.msnotifications.sqsconsumer.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msnotifications.model.entity.EvaluacionCompletadaEvent;
import com.msnotifications.usecase.ProcesarNotificacionUseCase;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;

@ApplicationScoped
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    @Inject SqsAsyncClient sqsClient;
    @Inject ProcesarNotificacionUseCase useCase;
    @Inject ObjectMapper objectMapper;
    @Inject Vertx vertx;

    @ConfigProperty(name = "sqs.queue.url")
    String queueUrl;

    @Scheduled(every = "20s", delayed = "30s")
    public Uni<Void> procesarMensajes() {
        var ctx = vertx.getOrCreateContext();

        return Uni.createFrom().completionStage(() ->
                sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(5)
                        .build()))
                // Vuelve al contexto Vert.x tras el thread pool del AWS SDK
                .emitOn(cmd -> ctx.runOnContext(v -> cmd.run()))
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

    Uni<Void> procesarYEliminar(Message msg) {
        return deserializar(msg)
                .chain(evento -> useCase.procesar(evento, msg.messageId()))
                .chain(() -> eliminar(msg.receiptHandle()))
                .onFailure().invoke(e ->
                        log.error("Error procesando mensaje {}: {}", msg.messageId(), e.getMessage()))
                .onFailure().recoverWithNull();
    }

    private Uni<EvaluacionCompletadaEvent> deserializar(Message msg) {
        try {
            return Uni.createFrom().item(
                    objectMapper.readValue(msg.body(), EvaluacionCompletadaEvent.class));
        } catch (Exception e) {
            return Uni.createFrom().failure(e);
        }
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
