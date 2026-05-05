# C4 — Nivel 1: Diagrama de Contexto del Sistema

## Descripción

El diagrama de contexto muestra el sistema de evaluación de créditos como una caja negra, sus usuarios directos y los sistemas externos con los que interactúa.

---

## Diagrama C4 Context (PlantUML)

```plantuml
@startuml C4_Context_CreditEvaluation
!include https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/master/C4_Context.puml

LAYOUT_WITH_LEGEND()

title Diagrama de Contexto — Sistema de Evaluación de Créditos

' ── Actores (Personas) ──────────────────────────────────────
Person(analista, "Analista de Crédito", "Usuario con rol ANALYST.\nEnvía solicitudes y consulta resultados.")
Person(administrador, "Administrador", "Usuario con rol ADMIN.\nGestiona usuarios, roles y configuración.")
Person(solicitante, "Solicitante de Crédito", "Ciudadano que solicita\nfinanciamiento. Recibe la\nrespuesta por email.")

' ── Sistema Principal ────────────────────────────────────────
System(sistema, "Sistema de Evaluación de Créditos", "Evalúa solicitudes de crédito,\norquesta servicios de riesgo,\nautentifica usuarios y notifica\nel resultado por email.")

' ── Sistemas Externos ────────────────────────────────────────
System_Ext(aws_sqs, "AWS SQS", "Cola de mensajes gestionada.\nDesacopla la evaluación de la\nnotificación al solicitante.")
System_Ext(aws_ses, "AWS SES", "Servicio de envío de email.\nEntrega los correos de\naprobación o rechazo.")

' ── Relaciones ───────────────────────────────────────────────
Rel(analista, sistema, "Accede vía navegador web", "HTTPS / REST")
Rel(administrador, sistema, "Gestiona usuarios y roles", "HTTPS / REST")
Rel(solicitante, sistema, "Recibe resultado de\nevaluación", "Email")

Rel(sistema, aws_sqs, "Publica eventos de evaluación\ncompletada", "AWS SDK / HTTPS")
Rel(aws_sqs, sistema, "Worker consume mensajes\npendientes de notificación", "AWS SDK / Polling")
Rel(sistema, aws_ses, "Envía emails de resultado\n(aprobado/rechazado)", "AWS SDK / SMTP")
Rel(aws_ses, solicitante, "Entrega email al destinatario", "SMTP")

@enduml
```

---

## Diagrama ASCII (referencia rápida)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    SISTEMA DE EVALUACIÓN DE CRÉDITOS                 │
│                         [C4 — Level 1: Context]                      │
└─────────────────────────────────────────────────────────────────────┘

 ┌──────────────┐           HTTPS/REST          ┌──────────────────────┐
 │   Analista   │ ─────────────────────────────> │                      │
 │  de Crédito  │                                │  SISTEMA DE          │
 └──────────────┘                                │  EVALUACIÓN DE       │
                                                 │  CRÉDITOS            │
 ┌──────────────┐           HTTPS/REST           │                      │
 │Administrador │ ─────────────────────────────> │  (Frontend React +   │
 │              │                                │   Microservicios     │
 └──────────────┘                                │   Quarkus + Auth +   │
                                                 │   Notificaciones)    │
 ┌──────────────┐                                │                      │
 │ Solicitante  │ <── Email (resultado) ──────── │                      │
 │  de Crédito  │                                └──────────┬───────────┘
 └──────────────┘                                           │
                                                            │ AWS SDK (publish)
                                                            ▼
                                                 ┌──────────────────────┐
                                                 │      AWS SQS         │
                                                 │  (cola de eventos)   │
                                                 └──────────┬───────────┘
                                                            │ polling
                                                            ▼
                                                 ┌──────────────────────┐
                                                 │      AWS SES         │
                                                 │  (envío de email)    │
                                                 └──────────────────────┘
```

---

## Actores y Sistemas — Tabla Resumen

| Elemento | Tipo | Descripción | Tecnología |
|----------|------|-------------|-----------|
| Analista de Crédito | Persona | Opera el frontend para evaluar solicitudes | Navegador web |
| Administrador | Persona | Gestiona usuarios, roles y configuración del sistema | Navegador web |
| Solicitante de Crédito | Persona (externo) | No interactúa directamente; recibe el resultado por email | Email |
| Sistema de Evaluación de Créditos | **Sistema propio** | Núcleo: UI + Microservicios + BD + Auth + Notificaciones | React + Quarkus + PostgreSQL |
| AWS SQS | Sistema externo | Cola de mensajes para desacoplar evaluación y notificación | AWS Managed Service |
| AWS SES | Sistema externo | Servicio de email transaccional | AWS Managed Service |

---

## Notas de Diseño del Contexto

### ¿Por qué el Solicitante no interactúa directamente con el sistema?
El solicitante es quien se beneficia del resultado pero no opera el sistema directamente: en el modelo de negocio actual, el analista ingresa los datos en nombre del solicitante. El solicitante solo recibe la notificación por email con el veredicto.

### ¿Por qué AWS SQS y no llamada directa a email?
El desacoplamiento asíncrono via SQS garantiza:
1. **Resiliencia**: si SES falla, el mensaje persiste en la cola y se reintenta.
2. **Rendimiento**: el endpoint de evaluación responde en < 5s sin esperar el envío del email.
3. **Observabilidad**: SQS permite monitorear mensajes pendientes y fallidos (DLQ).

### Límites del sistema
El sistema **no** incluye:
- Consulta a burós de crédito reales (se usa mock)
- Portal de autoservicio para el solicitante
- Integración con core bancario
