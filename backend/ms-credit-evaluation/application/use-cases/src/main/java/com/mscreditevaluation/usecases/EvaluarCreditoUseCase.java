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
import com.mscreditevaluation.usecases.util.LogMask;
import io.smallrye.mutiny.Uni;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

public class EvaluarCreditoUseCase {

    private static final Logger log = LoggerFactory.getLogger(EvaluarCreditoUseCase.class);

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
        String cedulaMask = LogMask.cedula(cmd.cedula());

        log.debug("Iniciando evaluación cedula={} monto={} plazo={}a evaluadoPorId={}",
                cedulaMask, LogMask.monto(cmd.montoSolicitado()), cmd.plazoAnios(), cmd.evaluadoPorId());

        return riskService.consultarRiesgo(cmd.cedula())
                .invoke(riskData -> log.debug("Datos de riesgo obtenidos cedula={} score={} deudaMensual={}",
                        cedulaMask, riskData.score(), LogMask.monto(riskData.totalDeudaMensual())))
                .flatMap(riskData -> {
                    var score   = new ScoreRiesgo(riskData.score());
                    var deuda   = Dinero.usd(riskData.totalDeudaMensual());
                    var monto   = Dinero.usd(cmd.montoSolicitado());
                    var salario = Dinero.usd(cmd.salario());

                    EstadoEvaluacion estado = EvaluacionCredito.evaluar(
                            score, deuda, salario, monto, cmd.plazoAnios());

                    log.info("Decisión de crédito cedula={} estado={} score={} evaluadoPorId={}",
                            cedulaMask, estado, riskData.score(), cmd.evaluadoPorId());

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
                            .invoke(persistida -> log.debug("Evaluación persistida evaluacionId={} cedula={}",
                                    persistida.getId(), cedulaMask))
                            .flatMap(persistida ->
                                notificationPort.publicarEvaluacionCompletada(
                                        persistida, cmd.destinatarioEmail())
                                    .onFailure().invoke(e -> log.warn(
                                            "Publicación SQS fallida — evaluación completada sin notificación evaluacionId={} error={}",
                                            persistida.getId(), e.getMessage()))
                                    .onFailure().recoverWithNull()
                                    .map(v -> new EvaluacionCreditoResult(persistida))
                            );
                })
                .onFailure().invoke(e -> log.error("Error en evaluación crédito cedula={} error={}",
                        cedulaMask, e.getMessage(), e));
    }

    public Uni<EvaluacionCreditoResult> buscarPorId(UUID id) {
        log.debug("Buscando evaluación id={}", id);
        return repository.buscarPorId(id)
                .map(opt -> opt
                        .map(EvaluacionCreditoResult::new)
                        .orElseThrow(() -> new EvaluacionNotFoundException(id)));
    }

    public Uni<List<EvaluacionCreditoResult>> listarTodas(int page, int size) {
        log.debug("Listando evaluaciones page={} size={}", page, size);
        return repository.listarTodas(page, size)
                .invoke(list -> log.debug("Evaluaciones obtenidas cantidad={} page={}", list.size(), page))
                .map(list -> list.stream().map(EvaluacionCreditoResult::new).toList());
    }
}
