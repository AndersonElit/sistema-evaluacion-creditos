package com.mscreditevaluation.postgres.repository;

import com.mscreditevaluation.model.entity.EvaluacionCredito;
import com.mscreditevaluation.model.port.EvaluacionCreditoRepository;
import com.mscreditevaluation.postgres.entity.CreditEvaluationEntity;
import com.mscreditevaluation.postgres.mapper.CreditEvaluationMapper;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class CreditEvaluationRepositoryAdapter implements EvaluacionCreditoRepository {

    private static final Logger log = LoggerFactory.getLogger(CreditEvaluationRepositoryAdapter.class);

    @Inject
    CreditEvaluationMapper mapper;

    @Override
    @WithTransaction
    public Uni<EvaluacionCredito> guardar(EvaluacionCredito evaluacion) {
        log.debug("Persistiendo evaluación cedula={} estado={}",
                maskCedula(evaluacion.getCedula().valor()), evaluacion.getEstadoFinal());
        CreditEvaluationEntity entity = mapper.toEntity(evaluacion);
        return entity.<CreditEvaluationEntity>persistAndFlush()
                .invoke(e -> log.debug("Evaluación persistida evaluacionId={}", e.id))
                .map(e -> evaluacion)
                .onFailure().invoke(e -> log.error("Error persistiendo evaluación cedula={} error={}",
                        maskCedula(evaluacion.getCedula().valor()), e.getMessage(), e));
    }

    @Override
    @WithTransaction
    public Uni<Optional<EvaluacionCredito>> buscarPorId(UUID id) {
        log.debug("Buscando evaluación por id={}", id);
        return CreditEvaluationEntity.<CreditEvaluationEntity>findById(id)
                .map(e -> Optional.ofNullable(e).map(mapper::toDomain))
                .onFailure().invoke(e -> log.error("Error buscando evaluación id={} error={}", id, e.getMessage(), e));
    }

    @Override
    @WithTransaction
    public Uni<List<EvaluacionCredito>> listarTodas(int page, int size) {
        log.debug("Listando evaluaciones page={} size={}", page, size);
        return CreditEvaluationEntity.<CreditEvaluationEntity>findAll()
                .page(page, size)
                .list()
                .invoke(list -> log.debug("Evaluaciones obtenidas cantidad={}", list.size()))
                .map(list -> list.stream().map(mapper::toDomain).toList())
                .onFailure().invoke(e -> log.error("Error listando evaluaciones page={} error={}", page, e.getMessage(), e));
    }

    private static String maskCedula(String cedula) {
        if (cedula == null || cedula.length() != 10) return "[cédula-inválida]";
        return cedula.substring(0, 2) + "******" + cedula.substring(8);
    }
}
