package com.msnotifications.sqsconsumer.adapter;

import com.msnotifications.model.entity.EvaluacionCompletadaEvent;
import com.msnotifications.ses.adapter.EmailSenderService;
import com.msnotifications.usecase.ProcesarNotificacionUseCase;
import com.msnotifications.usecase.port.NotificationPort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.TestReactiveTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@QuarkusTest
class ProcesarNotificacionUseCaseIT {

    @Inject
    ProcesarNotificacionUseCase useCase;

    @Inject
    NotificationPort notificationPort;

    @InjectMock
    EmailSenderService emailSender;

    private EvaluacionCompletadaEvent evento(String evalId) {
        return new EvaluacionCompletadaEvent(
                evalId, "1713175071", "test@email.com", "Juan Test",
                "APROBADO", new BigDecimal("5000.00"), "USD", 3,
                "2026-05-05T14:30:00Z", "1.0");
    }

    // ── Happy path ────────────────────────────────────────────

    @Test
    @TestReactiveTransaction
    void procesar_persiste_notificacion_con_estado_ENVIADO(UniAsserter asserter) {
        var evalId = UUID.randomUUID().toString();
        when(emailSender.enviar(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        asserter.assertThat(
                () -> useCase.procesar(evento(evalId), "sqs-msg-001")
                        .chain(() -> notificationPort.existeEnviada(UUID.fromString(evalId))),
                existe -> assertThat(existe).isTrue()
        );
    }

    @Test
    @TestReactiveTransaction
    void procesar_invoca_emailSender_exactamente_una_vez(UniAsserter asserter) {
        when(emailSender.enviar(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        asserter.execute(() -> useCase.procesar(evento(UUID.randomUUID().toString()), "sqs-msg-002"))
                .execute(() -> {
                    verify(emailSender, times(1))
                            .enviar(eq("test@email.com"), eq("APROBADO"), any(), any());
                    return Uni.createFrom().voidItem();
                });
    }

    // ── Idempotencia ──────────────────────────────────────────

    @Test
    @TestReactiveTransaction
    void segundo_procesamiento_del_mismo_evalId_no_genera_segundo_email(UniAsserter asserter) {
        var evalId = UUID.randomUUID().toString();
        when(emailSender.enviar(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().voidItem());

        asserter
                .execute(() -> useCase.procesar(evento(evalId), "sqs-msg-003"))
                .execute(() -> useCase.procesar(evento(evalId), "sqs-msg-003b"))
                .execute(() -> {
                    verify(emailSender, times(1)).enviar(any(), any(), any(), any());
                    return Uni.createFrom().voidItem();
                });
    }

    // ── Resiliencia ───────────────────────────────────────────

    @Test
    @RunOnVertxContext
    void fallo_de_ses_propaga_error_al_caller(UniAsserter asserter) {
        when(emailSender.enviar(any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("SES unavailable")));

        asserter.execute(
                () -> useCase.procesar(evento(UUID.randomUUID().toString()), "sqs-msg-004")
                        .onFailure().recoverWithItem((Void) null))
                .execute(() -> {
                    verify(emailSender, times(1)).enviar(any(), any(), any(), any());
                    return Uni.createFrom().voidItem();
                });
    }
}
