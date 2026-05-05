package com.mscreditevaluation.postgres.repository;

import com.mscreditevaluation.model.entity.EstadoEvaluacion;
import com.mscreditevaluation.model.entity.EvaluacionCredito;
import com.mscreditevaluation.model.valueobject.Cedula;
import com.mscreditevaluation.model.valueobject.Dinero;
import com.mscreditevaluation.model.valueobject.ScoreRiesgo;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class CreditEvaluationRepositoryIT {

    @Inject
    CreditEvaluationRepositoryAdapter repository;

    private EvaluacionCredito evaluacionValida() {
        return EvaluacionCredito.builder()
                .cedula(new Cedula("1713175071"))
                .montoSolicitado(Dinero.usd(new BigDecimal("5000.00")))
                .plazoAnios(3)
                .salario(Dinero.usd(new BigDecimal("2000.00")))
                .scoreRiesgo(new ScoreRiesgo(85))
                .deudaMensual(Dinero.usd(new BigDecimal("200.00")))
                .estadoFinal(EstadoEvaluacion.APROBADO)
                .evaluadoPorId(UUID.randomUUID())
                .build();
    }

    @Test
    @RunOnVertxContext
    void guardar_persiste_y_asigna_id(UniAsserter asserter) {
        asserter.assertThat(
                () -> repository.guardar(evaluacionValida()),
                guardada -> {
                    assertThat(guardada.getId()).isNotNull();
                    assertThat(guardada.getCedula().valor()).isEqualTo("1713175071");
                    assertThat(guardada.getEstadoFinal()).isEqualTo(EstadoEvaluacion.APROBADO);
                }
        );
    }

    @Test
    @RunOnVertxContext
    void buscarPorId_retorna_evaluacion_persistida(UniAsserter asserter) {
        EvaluacionCredito eval = evaluacionValida();
        asserter
                .execute(() -> repository.guardar(eval))
                .assertThat(
                        () -> repository.buscarPorId(eval.getId()),
                        encontrada -> {
                            assertThat(encontrada).isPresent();
                            assertThat(encontrada.get().getCedula().valor()).isEqualTo("1713175071");
                            assertThat(encontrada.get().getScoreRiesgo().valor()).isEqualTo(85);
                        }
                );
    }

    @Test
    @RunOnVertxContext
    void buscarPorId_retorna_empty_para_id_inexistente(UniAsserter asserter) {
        asserter.assertThat(
                () -> repository.buscarPorId(UUID.randomUUID()),
                resultado -> assertThat(resultado).isEmpty()
        );
    }

    @Test
    @RunOnVertxContext
    void listarTodas_retorna_evaluaciones_paginadas(UniAsserter asserter) {
        asserter
                .execute(() -> repository.guardar(evaluacionValida()))
                .execute(() -> repository.guardar(evaluacionValida()))
                .execute(() -> repository.guardar(evaluacionValida()))
                .assertThat(
                        () -> repository.listarTodas(0, 10),
                        lista -> assertThat(lista).hasSizeGreaterThanOrEqualTo(3)
                );
    }

    @Test
    @RunOnVertxContext
    void evaluacion_rechazada_se_persiste_con_estado_correcto(UniAsserter asserter) {
        var evaluacion = EvaluacionCredito.builder()
                .cedula(new Cedula("1713175071"))
                .montoSolicitado(Dinero.usd(new BigDecimal("10000.00")))
                .plazoAnios(2)
                .salario(Dinero.usd(new BigDecimal("1000.00")))
                .scoreRiesgo(new ScoreRiesgo(45))
                .deudaMensual(Dinero.usd(new BigDecimal("400.00")))
                .estadoFinal(EstadoEvaluacion.RECHAZADO)
                .evaluadoPorId(UUID.randomUUID())
                .build();

        asserter
                .execute(() -> repository.guardar(evaluacion))
                .assertThat(
                        () -> repository.buscarPorId(evaluacion.getId()),
                        encontrada -> {
                            assertThat(encontrada).isPresent();
                            assertThat(encontrada.get().getEstadoFinal()).isEqualTo(EstadoEvaluacion.RECHAZADO);
                        }
                );
    }
}
