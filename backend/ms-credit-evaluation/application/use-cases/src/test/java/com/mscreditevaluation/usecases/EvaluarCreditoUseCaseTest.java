package com.mscreditevaluation.usecases;

import com.mscreditevaluation.model.entity.EstadoEvaluacion;
import com.mscreditevaluation.model.entity.EvaluacionCredito;
import com.mscreditevaluation.model.port.EvaluacionCreditoRepository;
import com.mscreditevaluation.model.port.NotificationPort;
import com.mscreditevaluation.model.port.RiskServicePort;
import com.mscreditevaluation.usecases.command.SolicitudCreditoCommand;
import com.mscreditevaluation.usecases.result.EvaluacionCreditoResult;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvaluarCreditoUseCaseTest {

    @Mock EvaluacionCreditoRepository repository;
    @Mock RiskServicePort             riskService;
    @Mock NotificationPort            notificationPort;

    EvaluarCreditoUseCase useCase;

    private static final UUID EVALUADOR = UUID.randomUUID();

    private static final SolicitudCreditoCommand CMD = new SolicitudCreditoCommand(
            "1713175071", new BigDecimal("5000.00"), 3,
            new BigDecimal("2000.00"), EVALUADOR, "solicitante@email.com");

    @BeforeEach
    void setUp() {
        useCase = new EvaluarCreditoUseCase(repository, riskService, notificationPort);
        lenient().when(repository.guardar(any())).thenAnswer(inv ->
                Uni.createFrom().item(inv.<EvaluacionCredito>getArgument(0)));
        lenient().when(notificationPort.publicarEvaluacionCompletada(any(), any()))
                .thenReturn(Uni.createFrom().voidItem());
    }

    @Test
    void ejecutar_aprobado_con_score_alto() {
        when(riskService.consultarRiesgo("1713175071"))
                .thenReturn(Uni.createFrom().item(
                        new RiskServicePort.RiskData(85, new BigDecimal("200.00"))));

        var result = useCase.ejecutar(CMD).await().indefinitely();

        assertThat(result.evaluacion().getEstadoFinal()).isEqualTo(EstadoEvaluacion.APROBADO);
        assertThat(result.evaluacion().getCedula().valor()).isEqualTo("1713175071");
    }

    @Test
    void ejecutar_rechazado_con_score_70() {
        when(riskService.consultarRiesgo(anyString()))
                .thenReturn(Uni.createFrom().item(
                        new RiskServicePort.RiskData(70, new BigDecimal("50.00"))));

        var result = useCase.ejecutar(CMD).await().indefinitely();

        assertThat(result.evaluacion().getEstadoFinal()).isEqualTo(EstadoEvaluacion.RECHAZADO);
    }

    @Test
    void ejecutar_llama_a_repositorio_exactamente_una_vez() {
        when(riskService.consultarRiesgo(anyString()))
                .thenReturn(Uni.createFrom().item(
                        new RiskServicePort.RiskData(80, BigDecimal.ONE)));

        useCase.ejecutar(CMD).await().indefinitely();

        verify(repository, times(1)).guardar(any(EvaluacionCredito.class));
    }

    @Test
    void ejecutar_publica_notificacion_con_email_del_comando() {
        when(riskService.consultarRiesgo(anyString()))
                .thenReturn(Uni.createFrom().item(
                        new RiskServicePort.RiskData(80, BigDecimal.ONE)));

        useCase.ejecutar(CMD).await().indefinitely();

        verify(notificationPort, times(1))
                .publicarEvaluacionCompletada(any(), eq("solicitante@email.com"));
    }

    @Test
    void ejecutar_guarda_evaluadoPorId_del_comando() {
        when(riskService.consultarRiesgo(anyString()))
                .thenReturn(Uni.createFrom().item(
                        new RiskServicePort.RiskData(80, BigDecimal.ONE)));

        useCase.ejecutar(CMD).await().indefinitely();

        var captor = ArgumentCaptor.forClass(EvaluacionCredito.class);
        verify(repository).guardar(captor.capture());
        assertThat(captor.getValue().getEvaluadoPorId()).isEqualTo(EVALUADOR);
    }

    @Test
    void ejecutar_lanza_excepcion_si_cedula_es_invalida_sin_llamar_a_risk() {
        var cmdInvalido = new SolicitudCreditoCommand(
                "1234567890", new BigDecimal("5000"), 3,
                new BigDecimal("2000"), EVALUADOR, "e@e.com");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> useCase.ejecutar(cmdInvalido).await().indefinitely());

        verifyNoInteractions(riskService, repository, notificationPort);
    }

    @Test
    void ejecutar_no_persiste_cuando_risk_service_falla() {
        when(riskService.consultarRiesgo(anyString()))
                .thenReturn(Uni.createFrom().failure(
                        new RuntimeException("ms-risk timeout")));

        assertThatException()
                .isThrownBy(() -> useCase.ejecutar(CMD).await().indefinitely());

        verify(repository, never()).guardar(any());
        verify(notificationPort, never()).publicarEvaluacionCompletada(any(), any());
    }

    @Test
    void fallo_en_notificacion_no_impide_respuesta_al_cliente() {
        when(riskService.consultarRiesgo(anyString()))
                .thenReturn(Uni.createFrom().item(
                        new RiskServicePort.RiskData(85, new BigDecimal("100"))));
        when(notificationPort.publicarEvaluacionCompletada(any(), any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("SQS no disponible")));

        assertThatNoException()
                .isThrownBy(() -> useCase.ejecutar(CMD).await().indefinitely());

        verify(repository, times(1)).guardar(any());
    }

    @Test
    void ejecutar_almacena_score_y_deuda_retornados_por_risk_service() {
        when(riskService.consultarRiesgo(anyString()))
                .thenReturn(Uni.createFrom().item(
                        new RiskServicePort.RiskData(92, new BigDecimal("350.00"))));

        var result = useCase.ejecutar(CMD).await().indefinitely();

        assertThat(result.evaluacion().getScoreRiesgo().valor()).isEqualTo(92);
        assertThat(result.evaluacion().getDeudaMensual().cantidad())
                .isEqualByComparingTo("350.00");
    }
}
