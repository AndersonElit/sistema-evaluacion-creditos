package com.msnotifications.model.port;

import io.smallrye.mutiny.Uni;
import java.util.UUID;

public interface NotificationPort {
    Uni<Boolean> existeEnviada(UUID evaluacionId);
    Uni<UUID> crear(UUID evaluacionId, String destinatarioEmail,
                    String tipoNotificacion, String mensajeSqsId);
    Uni<Void> marcarEnviada(UUID id);
}
