package com.msnotifications.postgres.adapter;

import com.msnotifications.postgres.entity.NotificationEntity;
import com.msnotifications.model.port.NotificationPort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class NotificationAdapter implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(NotificationAdapter.class);

    @Override
    public Uni<Boolean> existeEnviada(UUID evaluacionId) {
        log.debug("Verificando idempotencia evaluacionId={}", evaluacionId);
        return NotificationEntity.count(
                "evaluacionId = ?1 AND estado = ?2",
                evaluacionId, NotificationEntity.EstadoNotif.ENVIADO)
                .map(n -> n > 0);
    }

    @Override
    public Uni<UUID> crear(UUID evaluacionId, String destinatarioEmail,
                            String tipoNotificacion, String mensajeSqsId) {
        log.debug("Creando notificación evaluacionId={} tipo={}", evaluacionId, tipoNotificacion);
        NotificationEntity notif = new NotificationEntity();
        notif.evaluacionId = evaluacionId;
        notif.destinatarioEmail = destinatarioEmail;
        notif.tipoNotificacion = NotificationEntity.TipoNotif.valueOf(tipoNotificacion);
        notif.mensajeSqsId = mensajeSqsId;
        return notif.<NotificationEntity>persist()
                .invoke(n -> log.debug("Notificación creada id={} evaluacionId={}", n.id, evaluacionId))
                .map(n -> n.id)
                .onFailure().invoke(e -> log.error("Error creando notificación evaluacionId={} error={}",
                        evaluacionId, e.getMessage(), e));
    }

    @Override
    public Uni<Void> marcarEnviada(UUID id) {
        log.debug("Marcando notificación como enviada id={}", id);
        return NotificationEntity.<NotificationEntity>findById(id)
                .invoke(n -> {
                    n.estado = NotificationEntity.EstadoNotif.ENVIADO;
                    n.enviadoEn = Instant.now();
                    n.intentos++;
                })
                .invoke(n -> log.debug("Notificación marcada como enviada id={}", id))
                .replaceWithVoid()
                .onFailure().invoke(e -> log.error("Error marcando notificación enviada id={} error={}",
                        id, e.getMessage(), e));
    }
}
