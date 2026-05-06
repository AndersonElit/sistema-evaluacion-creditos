package com.msnotifications.sqsconsumer.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msnotifications.usecase.ProcesarNotificacionUseCase;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@QuarkusTest
class NotificationConsumerIT {

    @Inject
    NotificationConsumer consumer;

    @InjectMock
    SqsAsyncClient sqsClient;

    @InjectMock
    ProcesarNotificacionUseCase useCase;

    private final ObjectMapper mapper = new ObjectMapper();

    private Message mensajeValido(String evaluacionId) throws Exception {
        var cuerpo = mapper.writeValueAsString(Map.of(
                "evaluacionId",      evaluacionId,
                "cedula",            "1713175071",
                "destinatarioEmail", "test@email.com",
                "estadoFinal",       "APROBADO",
                "montoSolicitado",   new BigDecimal("5000.00"),
                "moneda",            "USD",
                "plazoAnios",        3,
                "fechaEvaluacion",   "2026-05-05T14:30:00Z",
                "version",           "1.0"
        ));
        return Message.builder()
                .messageId(UUID.randomUUID().toString())
                .receiptHandle("rh-" + evaluacionId)
                .body(cuerpo)
                .build();
    }

    @BeforeEach
    void setUp() {
        when(sqsClient.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        DeleteMessageResponse.builder().build()));
    }

    // ── Happy path ────────────────────────────────────────────

    @Test
    @RunOnVertxContext
    void procesar_mensaje_valido_delega_al_usecase(UniAsserter asserter) throws Exception {
        var msg = mensajeValido(UUID.randomUUID().toString());

        when(useCase.procesar(any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        asserter.execute(() -> consumer.procesarYEliminar(msg))
                .execute(() -> {
                    verify(useCase, times(1)).procesar(any(), eq(msg.messageId()));
                    return Uni.createFrom().voidItem();
                });
    }

    @Test
    @RunOnVertxContext
    void procesar_elimina_mensaje_de_sqs_tras_exito(UniAsserter asserter) throws Exception {
        var msg = mensajeValido(UUID.randomUUID().toString());

        when(useCase.procesar(any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        asserter.execute(() -> consumer.procesarYEliminar(msg))
                .execute(() -> {
                    verify(sqsClient, times(1)).deleteMessage(any(DeleteMessageRequest.class));
                    return Uni.createFrom().voidItem();
                });
    }

    // ── Resiliencia ───────────────────────────────────────────

    @Test
    @RunOnVertxContext
    void mensaje_malformado_no_elimina_mensaje_de_sqs(UniAsserter asserter) {
        var msgMalformado = Message.builder()
                .messageId("bad-msg")
                .receiptHandle("rh-bad")
                .body("{ esto no es json valido }")
                .build();

        asserter.execute(() -> consumer.procesarYEliminar(msgMalformado))
                .execute(() -> {
                    verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
                    return Uni.createFrom().voidItem();
                });
    }

    @Test
    @RunOnVertxContext
    void fallo_del_usecase_no_elimina_mensaje_de_sqs(UniAsserter asserter) throws Exception {
        var msg = mensajeValido(UUID.randomUUID().toString());

        when(useCase.procesar(any(), any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("use case failure")));

        asserter.execute(() -> consumer.procesarYEliminar(msg))
                .execute(() -> {
                    verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
                    return Uni.createFrom().voidItem();
                });
    }

    @Test
    @RunOnVertxContext
    void cola_vacia_no_llama_al_usecase(UniAsserter asserter) {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of()).build()));

        asserter.execute(() -> consumer.procesarMensajes())
                .execute(() -> {
                    verifyNoInteractions(useCase);
                    return Uni.createFrom().voidItem();
                });
    }
}
