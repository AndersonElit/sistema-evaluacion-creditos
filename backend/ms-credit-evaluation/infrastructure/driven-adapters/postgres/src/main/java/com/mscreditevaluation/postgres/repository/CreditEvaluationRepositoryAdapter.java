package com.mscreditevaluation.postgres.repository;

import com.mscreditevaluation.model.entity.EvaluacionCredito;
import com.mscreditevaluation.model.port.EvaluacionCreditoRepository;
import com.mscreditevaluation.postgres.entity.CreditEvaluationEntity;
import com.mscreditevaluation.postgres.mapper.CreditEvaluationMapper;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CreditEvaluationRepositoryAdapter implements EvaluacionCreditoRepository {

    @Inject
    CreditEvaluationMapper mapper;

    @Override
    @WithTransaction
    public Uni<EvaluacionCredito> guardar(EvaluacionCredito evaluacion) {
        CreditEvaluationEntity entity = mapper.toEntity(evaluacion);
        return entity.<CreditEvaluationEntity>persistAndFlush()
                .map(e -> evaluacion);
    }

    @Override
    @WithTransaction
    public Uni<Optional<EvaluacionCredito>> buscarPorId(UUID id) {
        return CreditEvaluationEntity.<CreditEvaluationEntity>findById(id)
                .map(e -> Optional.ofNullable(e).map(mapper::toDomain));
    }

    @Override
    @WithTransaction
    public Uni<List<EvaluacionCredito>> listarTodas(int page, int size) {
        return CreditEvaluationEntity.<CreditEvaluationEntity>findAll()
                .page(page, size)
                .list()
                .map(list -> list.stream().map(mapper::toDomain).toList());
    }
}
