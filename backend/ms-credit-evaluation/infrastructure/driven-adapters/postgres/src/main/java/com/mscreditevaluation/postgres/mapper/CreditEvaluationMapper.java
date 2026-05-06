package com.mscreditevaluation.postgres.mapper;

import com.mscreditevaluation.model.entity.EstadoEvaluacion;
import com.mscreditevaluation.model.entity.EvaluacionCredito;
import com.mscreditevaluation.model.valueobject.Cedula;
import com.mscreditevaluation.model.valueobject.Dinero;
import com.mscreditevaluation.model.valueobject.ScoreRiesgo;
import com.mscreditevaluation.postgres.entity.CreditEvaluationEntity;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CreditEvaluationMapper {

    public CreditEvaluationEntity toEntity(EvaluacionCredito d) {
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

    public EvaluacionCredito toDomain(CreditEvaluationEntity e) {
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
