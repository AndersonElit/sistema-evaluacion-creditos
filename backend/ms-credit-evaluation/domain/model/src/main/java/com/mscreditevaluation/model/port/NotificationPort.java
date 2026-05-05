package com.mscreditevaluation.model.port;

import com.mscreditevaluation.model.entity.EvaluacionCredito;
import io.smallrye.mutiny.Uni;

public interface NotificationPort {
    Uni<Void> publicarEvaluacionCompletada(EvaluacionCredito evaluacion, String destinatarioEmail);
}
