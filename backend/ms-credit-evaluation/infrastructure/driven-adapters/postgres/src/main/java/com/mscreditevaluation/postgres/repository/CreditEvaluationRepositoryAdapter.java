package com.mscreditevaluation.postgres.repository;

import com.mscreditevaluation.model.entity.EstadoEvaluacion;
import com.mscreditevaluation.model.entity.EvaluacionCredito;
import com.mscreditevaluation.model.port.EvaluacionCreditoRepository;
import com.mscreditevaluation.model.valueobject.Cedula;
import com.mscreditevaluation.model.valueobject.Dinero;
import com.mscreditevaluation.model.valueobject.ScoreRiesgo;
import com.mscreditevaluation.postgres.entity.CreditEvaluationEntity;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CreditEvaluationRepositoryAdapter implements EvaluacionCreditoRepository {

    @Override
    @WithTransaction
    public Uni<EvaluacionCredito> guardar(EvaluacionCredito evaluacion) {
        CreditEvaluationEntity entity = toEntity(evaluacion);
        return entity.<CreditEvaluationEntity>persistAndFlush()
                .map(e -> evaluacion);
    }

    @Override
    @WithTransaction
    public Uni<Optional<EvaluacionCredito>> buscarPorId(UUID id) {
        return CreditEvaluationEntity.<CreditEvaluationEntity>findById(id)
                .map(e -> Optional.ofNullable(e).map(this::toDomain));
    }

    @Override
    @WithTransaction
    public Uni<List<EvaluacionCredito>> listarTodas(int page, int size) {
        return CreditEvaluationEntity.<CreditEvaluationEntity>findAll()
                .page(page, size)
                .list()
                .map(list -> list.stream().map(this::toDomain).toList());
    }

    private CreditEvaluationEntity toEntity(EvaluacionCredito d) {
        var e = new CreditEvaluationEntity();
        e.id = d.getId();
        e.cedula = d.getCedula().valor();
        e.montoSolicitado = d.getMontoSolicitado().cantidad();
        e.plazoAnios = d.getPlazoAnios();
        e.salario = d.getSalario().cantidad();
        e.scoreRiesgo = d.getScoreRiesgo().valor();
        e.deudaMensualTotal = d.getDeudaMensual().cantidad();
        e.estadoFinal = CreditEvaluationEntity.EstadoEvaluacionJpa
                .valueOf(d.getEstadoFinal().name());
        e.fechaEvaluacion = d.getFechaEvaluacion();
        e.evaluadoPorId = d.getEvaluadoPorId();
        return e;
    }

    private EvaluacionCredito toDomain(CreditEvaluationEntity e) {
        return EvaluacionCredito.builder()
                .cedula(new Cedula(e.cedula))
                .montoSolicitado(Dinero.usd(e.montoSolicitado))
                .plazoAnios(e.plazoAnios)
                .salario(Dinero.usd(e.salario))
                .scoreRiesgo(new ScoreRiesgo(e.scoreRiesgo))
                .deudaMensual(Dinero.usd(e.deudaMensualTotal))
                .estadoFinal(EstadoEvaluacion.valueOf(e.estadoFinal.name()))
                .evaluadoPorId(e.evaluadoPorId)
                .build();
    }
}
