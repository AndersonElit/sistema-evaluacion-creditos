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
System(sistema, "Sistema de Evaluación de Créditos", "Evalúa solicitudes de crédito,\norquesta servicios de riesgo y\nnotifica el resultado por email.")
System(auth_sistema, "Sistema de Identidad y Acceso", "Autentica usuarios, gestiona\nroles y emite tokens JWT.")

' ── Sistemas Externos ────────────────────────────────────────
System_Ext(aws_sqs, "AWS SQS", "Cola de mensajes gestionada.\nDesacopla la evaluación de la\nnotificación al solicitante.")
System_Ext(aws_ses, "AWS SES", "Servicio de envío de email.\nEntrega los correos de\naprobación o rechazo.")

' ── Relaciones ───────────────────────────────────────────────
Rel(analista, auth_sistema, "Inicia sesión", "HTTPS / REST")
Rel(administrador, auth_sistema, "Gestiona usuarios y roles", "HTTPS / REST")
Rel(analista, sistema, "Evalúa solicitudes\n(con JWT de Auth)", "HTTPS / REST")
Rel(administrador, sistema, "Consulta evaluaciones\n(con JWT de Auth)", "HTTPS / REST")
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
┌──────────────────────────────────────────────────────────────────────┐
│                  SISTEMA DE EVALUACIÓN DE CRÉDITOS                    │
│                       [C4 — Level 1: Context]                         │
└──────────────────────────────────────────────────────────────────────┘

 ┌──────────────┐   HTTPS/REST (login)   ┌──────────────────────────┐
 │   Analista   │ ──────────────────────>│  SISTEMA DE IDENTIDAD    │
 │  de Crédito  │ <── JWT ──────────────│  Y ACCESO                │
 └──────┬───────┘                        │  (MS-C — Auth)           │
        │                                │                          │
 ┌──────┴───────┐   HTTPS/REST (login)   │  • Login / JWT           │
 │Administrador │ ──────────────────────>│  • Gestión de usuarios   │
 │              │ <── JWT ──────────────│  • Roles: ADMIN,         │
 └──────┬───────┘                        │    ANALYST, VIEWER       │
        │                                └──────────────────────────┘
        │ HTTPS/REST + JWT Bearer
        ▼
 ┌──────────────────────────────────────┐
 │  SISTEMA DE EVALUACIÓN DE CRÉDITOS   │
 │  (MS-A — Orquestador + Frontend)     │
 │                                      │
 │  • Evalúa solicitudes de crédito     │
 │  • Consulta score y deudas (MS-B)    │
 │  • Persiste evaluaciones (creditos_db│
 │  • Notifica resultado (SQS + SES)    │
 └──────────────┬───────────────────────┘
                │ AWS SDK (publish)               ┌──────────────┐
                ▼                                 │  Solicitante │
 ┌──────────────────────┐                         │  de Crédito  │
 │      AWS SQS         │                         └──────┬───────┘
 │  (cola de eventos)   │                                ▲
 └──────────┬───────────┘                                │ Email
            │ polling                                     │
            ▼                                            │
 ┌──────────────────────┐                               │
 │      AWS SES         │ ──────────────────────────────┘
 │  (envío de email)    │
 └──────────────────────┘
```

---

## Actores y Sistemas — Tabla Resumen

| Elemento | Tipo | Descripción | Tecnología |
|----------|------|-------------|-----------|
| Analista de Crédito | Persona | Opera el frontend para evaluar solicitudes | Navegador web |
| Administrador | Persona | Gestiona usuarios y roles vía MS-C | Navegador web |
| Solicitante de Crédito | Persona (externo) | No interactúa directamente; recibe el resultado por email | Email |
| Sistema de Identidad y Acceso | **Sistema propio (MS-C)** | Autenticación, autorización, gestión de usuarios y roles | Quarkus + PostgreSQL (auth_db) |
| Sistema de Evaluación de Créditos | **Sistema propio (MS-A + MS-B)** | Orquestación de evaluaciones, riesgos y notificaciones | React + Quarkus + PostgreSQL (creditos_db) |
| AWS SQS | Sistema externo | Cola de mensajes para desacoplar evaluación y notificación | AWS Managed Service |
| AWS SES | Sistema externo | Servicio de email transaccional | AWS Managed Service |

---

## Notas de Diseño del Contexto

### ¿Por qué el Solicitante no interactúa directamente con el sistema?
El solicitante es quien se beneficia del resultado pero no opera el sistema directamente: en el modelo de negocio actual, el analista ingresa los datos en nombre del solicitante. El solicitante solo recibe la notificación por email con el veredicto.

### ¿Por qué Auth es un sistema separado?
El Sistema de Identidad y Acceso (MS-C) está desacoplado del Sistema de Evaluación de Créditos porque:
1. **Responsabilidad única**: MS-A no mezcla lógica de negocio (evaluar créditos) con lógica de identidad (gestionar usuarios).
2. **Escalabilidad independiente**: MS-C puede evolucionar sin afectar a MS-A.
3. **Reutilización**: MS-C podría emitir tokens para otros servicios futuros sin modificar MS-A.
4. El acoplamiento en runtime es **cero**: MS-A valida JWTs solo con la clave pública RSA, sin hacer llamadas HTTP a MS-C.

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
- SSO / federación con proveedores externos (MS-C usa usuarios internos)
