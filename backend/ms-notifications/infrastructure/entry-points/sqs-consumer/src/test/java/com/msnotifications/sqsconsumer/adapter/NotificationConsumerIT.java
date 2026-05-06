package com.msnotifications.sqsconsumer.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msnotifications.postgres.entity.NotificationEntity;
import com.msnotifications.postgres.repository.EmailSenderService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.TestReactiveTransaction;
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

import static org.assertj.core.api.Assertions.*;
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
    EmailSenderService emailSender;

    private final ObjectMapper mapper = new ObjectMapper();

    private Message mensajeValido(String evaluacionId) throws Exception {
        var evento = mapper.writeValueAsString(Map.of(
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
                .body(evento)
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
    @TestReactiveTransaction
    void procesar_mensaje_valido_guarda_notificacion_ENVIADO(UniAsserter asserter) throws Exception {
        var evalId = UUID.randomUUID().toString();
        var msg = mensajeValido(evalId);

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of(msg)).build()));
        when(emailSender.enviar(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        asserter.assertThat(
                () -> consumer.procesarMensajes()
                        .chain(() -> NotificationEntity.count(
                                "evaluacionId = ?1 AND estado = ?2",
                                UUID.fromString(evalId), NotificationEntity.EstadoNotif.ENVIADO)),
                count -> assertThat(count).isEqualTo(1L)
        );
    }

    @Test
    @TestReactiveTransaction
    void procesar_mensaje_envia_email_exactamente_una_vez(UniAsserter asserter) throws Exception {
        var msg = mensajeValido(UUID.randomUUID().toString());

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of(msg)).build()));
        when(emailSender.enviar(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        asserter.execute(() -> consumer.procesarMensajes())
                .execute(() -> {
                    verify(emailSender, times(1))
                            .enviar(eq("test@email.com"), eq("APROBADO"), any(), any());
                    return Uni.createFrom().voidItem();
                });
    }

    @Test
    @TestReactiveTransaction
    void procesar_elimina_mensaje_de_sqs_tras_exito(UniAsserter asserter) throws Exception {
        var msg = mensajeValido(UUID.randomUUID().toString());

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of(msg)).build()));
        when(emailSender.enviar(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        asserter.execute(() -> consumer.procesarMensajes())
                .execute(() -> {
                    verify(sqsClient, times(1))
                            .deleteMessage(any(DeleteMessageRequest.class));
                    return Uni.createFrom().voidItem();
                });
    }

    // ── Idempotencia ──────────────────────────────────────────

    @Test
    @TestReactiveTransaction
    void mensaje_duplicado_no_genera_segundo_email_ni_fila(UniAsserter asserter) throws Exception {
        var evalId = UUID.randomUUID().toString();
        var msg = mensajeValido(evalId);

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of(msg)).build()));
        when(emailSender.enviar(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        asserter
                .execute(() -> consumer.procesarMensajes())
                .execute(() -> consumer.procesarMensajes())
                .execute(() -> {
                    verify(emailSender, times(1)).enviar(any(), any(), any(), any());
                    return Uni.createFrom().voidItem();
                })
                .assertThat(
                        () -> NotificationEntity.count("evaluacionId = ?1", UUID.fromString(evalId)),
                        count -> assertThat(count).isEqualTo(1L)
                );
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

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder()
                                .messages(List.of(msgMalformado)).build()));

        asserter.execute(() -> consumer.procesarMensajes())
                .execute(() -> {
                    verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
                    return Uni.createFrom().voidItem();
                });
    }

    @Test
    @RunOnVertxContext
    void fallo_de_ses_no_elimina_mensaje_de_sqs(UniAsserter asserter) throws Exception {
        var msg = mensajeValido(UUID.randomUUID().toString());

        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of(msg)).build()));
        when(emailSender.enviar(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("SES unavailable")));

        asserter.execute(() -> consumer.procesarMensajes())
                .execute(() -> {
                    verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
                    return Uni.createFrom().voidItem();
                });
    }

    @Test
    @RunOnVertxContext
    void cola_vacia_no_llama_a_emailSender(UniAsserter asserter) {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        ReceiveMessageResponse.builder().messages(List.of()).build()));

        asserter.execute(() -> consumer.procesarMensajes())
                .execute(() -> {
                    verifyNoInteractions(emailSender);
                    return Uni.createFrom().voidItem();
                });
    }
}
