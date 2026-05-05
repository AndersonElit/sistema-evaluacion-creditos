package com.mscreditevaluation.model.port;

import com.mscreditevaluation.model.entity.EvaluacionCredito;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvaluacionCreditoRepository {
    Uni<EvaluacionCredito> guardar(EvaluacionCredito evaluacion);
    Uni<Optional<EvaluacionCredito>> buscarPorId(UUID id);
    Uni<List<EvaluacionCredito>> listarTodas(int page, int size);
}
