package com.mscreditevaluation.sqsproducer.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mscreditevaluation.model.entity.EvaluacionCredito;
import com.mscreditevaluation.model.port.NotificationPort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;

@ApplicationScoped
public class SqsNotificationPublisher implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(SqsNotificationPublisher.class);

    @Inject SqsAsyncClient sqsClient;
    @Inject ObjectMapper objectMapper;

    @ConfigProperty(name = "sqs.queue.url")
    String queueUrl;

    @Override
    public Uni<Void> publicarEvaluacionCompletada(EvaluacionCredito evaluacion,
                                                   String destinatarioEmail) {
        try {
            Map<String, Object> evento = Map.of(
                "evaluacionId",      evaluacion.getId().toString(),
                "cedula",            evaluacion.getCedula().valor(),
                "destinatarioEmail", destinatarioEmail,
                "estadoFinal",       evaluacion.getEstadoFinal().name(),
                "montoSolicitado",   evaluacion.getMontoSolicitado().cantidad(),
                "moneda",            "USD",
                "plazoAnios",        evaluacion.getPlazoAnios(),
                "fechaEvaluacion",   evaluacion.getFechaEvaluacion().toString(),
                "version",           "1.0"
            );
            String body = objectMapper.writeValueAsString(evento);

            return Uni.createFrom().completionStage(() ->
                    sqsClient.sendMessage(SendMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .messageBody(body)
                            .build()))
                    .invoke(r -> log.info("Evento publicado en SQS: evaluacionId={}, messageId={}",
                            evaluacion.getId(), r.messageId()))
                    .replaceWithVoid()
                    .onFailure().invoke(e -> log.error("Error publicando en SQS para evaluacion {}: {}",
                            evaluacion.getId(), e.getMessage()));
        } catch (Exception e) {
            log.error("Error serializando evento SQS: {}", e.getMessage());
            return Uni.createFrom().voidItem();
        }
    }
}
