package com.msnotifications.postgres.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ses.SesAsyncClient;
import software.amazon.awssdk.services.ses.model.*;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailSenderServiceTest {

    @Mock
    SesAsyncClient sesClient;

    @InjectMocks
    EmailSenderService emailSender;

    @Test
    void enviar_APROBADO_usa_asunto_con_APROBADA() {
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        SendEmailResponse.builder().messageId("ses-001").build()));

        emailSender.enviar("dest@email.com", "APROBADO",
                new BigDecimal("5000.00"), "2026-05-05T14:30:00Z")
                .await().indefinitely();

        var captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());

        var asunto = captor.getValue().message().subject().data();
        assertThat(asunto).containsIgnoringCase("APROBADA");
        assertThat(captor.getValue().destination().toAddresses())
                .containsExactly("dest@email.com");
    }

    @Test
    void enviar_RECHAZADO_usa_asunto_con_RECHAZADA() {
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        SendEmailResponse.builder().messageId("ses-002").build()));

        emailSender.enviar("dest@email.com", "RECHAZADO",
                new BigDecimal("3000.00"), "2026-05-05T14:30:00Z")
                .await().indefinitely();

        var captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());
        assertThat(captor.getValue().message().subject().data())
                .containsIgnoringCase("RECHAZADA");
    }

    @Test
    void enviar_llama_a_ses_exactamente_una_vez() {
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        SendEmailResponse.builder().messageId("ses-003").build()));

        emailSender.enviar("a@b.com", "APROBADO", BigDecimal.ONE, "2026-05-05T00:00:00Z")
                .await().indefinitely();

        verify(sesClient, times(1)).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void fallo_de_ses_propaga_excepcion() {
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        SesException.builder().message("SES error").build()));

        assertThatException()
                .isThrownBy(() -> emailSender.enviar(
                        "dest@email.com", "APROBADO",
                        BigDecimal.ONE, "2026-05-05T00:00:00Z").await().indefinitely());
    }

    @Test
    void cuerpo_html_APROBADO_contiene_monto() {
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        SendEmailResponse.builder().messageId("ses-004").build()));

        emailSender.enviar("dest@email.com", "APROBADO",
                new BigDecimal("7500.00"), "2026-05-05T14:30:00Z")
                .await().indefinitely();

        var captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());
        var html = captor.getValue().message().body().html().data();
        assertThat(html).contains("7,500.00");
    }
}
