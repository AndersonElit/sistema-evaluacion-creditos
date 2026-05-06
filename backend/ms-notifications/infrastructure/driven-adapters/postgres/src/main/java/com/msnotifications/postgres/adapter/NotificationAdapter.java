package com.msnotifications.postgres.adapter;

import com.msnotifications.postgres.entity.NotificationEntity;
import com.msnotifications.usecase.port.NotificationPort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class NotificationAdapter implements NotificationPort {

    @Override
    public Uni<Boolean> existeEnviada(UUID evaluacionId) {
        return NotificationEntity.count(
                "evaluacionId = ?1 AND estado = ?2",
                evaluacionId, NotificationEntity.EstadoNotif.ENVIADO)
                .map(n -> n > 0);
    }

    @Override
    public Uni<UUID> crear(UUID evaluacionId, String destinatarioEmail,
                            String tipoNotificacion, String mensajeSqsId) {
        NotificationEntity notif = new NotificationEntity();
        notif.evaluacionId = evaluacionId;
        notif.destinatarioEmail = destinatarioEmail;
        notif.tipoNotificacion = NotificationEntity.TipoNotif.valueOf(tipoNotificacion);
        notif.mensajeSqsId = mensajeSqsId;
        return notif.<NotificationEntity>persist().map(n -> n.id);
    }

    @Override
    public Uni<Void> marcarEnviada(UUID id) {
        return NotificationEntity.<NotificationEntity>findById(id)
                .invoke(n -> {
                    n.estado = NotificationEntity.EstadoNotif.ENVIADO;
                    n.enviadoEn = Instant.now();
                    n.intentos++;
                })
                .replaceWithVoid();
    }
}
