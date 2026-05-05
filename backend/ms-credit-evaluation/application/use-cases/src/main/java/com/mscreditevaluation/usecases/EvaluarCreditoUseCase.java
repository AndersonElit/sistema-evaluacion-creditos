package com.mscreditevaluation.usecases;

import com.mscreditevaluation.model.entity.EstadoEvaluacion;
import com.mscreditevaluation.model.entity.EvaluacionCredito;
import com.mscreditevaluation.model.port.EvaluacionCreditoRepository;
import com.mscreditevaluation.model.port.NotificationPort;
import com.mscreditevaluation.model.port.RiskServicePort;
import com.mscreditevaluation.model.valueobject.Cedula;
import com.mscreditevaluation.model.valueobject.Dinero;
import com.mscreditevaluation.model.valueobject.ScoreRiesgo;
import com.mscreditevaluation.usecases.command.SolicitudCreditoCommand;
import com.mscreditevaluation.usecases.exception.EvaluacionNotFoundException;
import com.mscreditevaluation.usecases.result.EvaluacionCreditoResult;
import io.smallrye.mutiny.Uni;

import java.util.List;
import java.util.UUID;

public class EvaluarCreditoUseCase {

    private final EvaluacionCreditoRepository repository;
    private final RiskServicePort riskService;
    private final NotificationPort notificationPort;

    public EvaluarCreditoUseCase(EvaluacionCreditoRepository repository,
                                  RiskServicePort riskService,
                                  NotificationPort notificationPort) {
        this.repository = repository;
        this.riskService = riskService;
        this.notificationPort = notificationPort;
    }

    public Uni<EvaluacionCreditoResult> ejecutar(SolicitudCreditoCommand cmd) {
        Cedula cedula = new Cedula(cmd.cedula());

        return riskService.consultarRiesgo(cmd.cedula())
                .flatMap(riskData -> {
                    var score   = new ScoreRiesgo(riskData.score());
                    var deuda   = Dinero.usd(riskData.totalDeudaMensual());
                    var monto   = Dinero.usd(cmd.montoSolicitado());
                    var salario = Dinero.usd(cmd.salario());

                    EstadoEvaluacion estado = EvaluacionCredito.evaluar(
                            score, deuda, salario, monto, cmd.plazoAnios());

                    EvaluacionCredito evaluacion = EvaluacionCredito.builder()
                            .cedula(cedula)
                            .montoSolicitado(monto)
                            .plazoAnios(cmd.plazoAnios())
                            .salario(salario)
                            .scoreRiesgo(score)
                            .deudaMensual(deuda)
                            .estadoFinal(estado)
                            .evaluadoPorId(cmd.evaluadoPorId())
                            .build();

                    return repository.guardar(evaluacion)
                            .flatMap(persistida ->
                                notificationPort.publicarEvaluacionCompletada(
                                        persistida, cmd.destinatarioEmail())
                                    .onFailure().recoverWithNull()
                                    .map(v -> new EvaluacionCreditoResult(persistida))
                            );
                });
    }

    public Uni<EvaluacionCreditoResult> buscarPorId(UUID id) {
        return repository.buscarPorId(id)
                .map(opt -> opt
                        .map(EvaluacionCreditoResult::new)
                        .orElseThrow(() -> new EvaluacionNotFoundException(id)));
    }

    public Uni<List<EvaluacionCreditoResult>> listarTodas(int page, int size) {
        return repository.listarTodas(page, size)
                .map(list -> list.stream().map(EvaluacionCreditoResult::new).toList());
    }
}
