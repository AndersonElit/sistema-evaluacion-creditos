# C4 — Nivel 2: Diagrama de Contenedores

## Descripción

Descompone el sistema en sus contenedores de ejecución: aplicaciones, bases de datos, colas y workers, mostrando cómo se comunican entre sí.

---

## Diagrama C4 Container (PlantUML)

```plantuml
@startuml C4_Container_CreditEvaluation
!include https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/master/C4_Container.puml

LAYOUT_WITH_LEGEND()

title Diagrama de Contenedores — Sistema de Evaluación de Créditos

' ── Personas ────────────────────────────────────────────────
Person(analista, "Analista / Admin", "Opera el sistema via\nnavegador web.")
Person(solicitante, "Solicitante", "Recibe email con resultado.")

' ── Boundary del Sistema ────────────────────────────────────
System_Boundary(sistema, "Sistema de Evaluación de Créditos") {

  Container(frontend, "Frontend SPA", "React 18 + TypeScript", "Interfaz web.\nFormulario de evaluación,\nlista de evaluaciones,\nlogin y gestión de usuarios.")

  Container(orchestrator, "Microservicio A — Orquestador", "Java 21 + Quarkus 3.x", "Expone la API REST principal.\nValida cédula (Módulo 10).\nOrquesta llamadas a Riesgos.\nAplica reglas de negocio.\nPersiste evaluaciones.\nPublica eventos en SQS.\nGestiona Auth (JWT).")

  Container(risk_service, "Microservicio B — Riesgos Mock", "Java 21 + Quarkus 3.x", "Expone score aleatorio (0–100)\ny lista de deudas por cédula.\nSimula latencia de 2s y 1.5s.")

  ContainerDb(postgres, "Base de Datos", "PostgreSQL 16", "Almacena evaluaciones,\nusuarios, roles y\nnotificaciones.")

  Container(notif_worker, "Notification Worker", "Quarkus Scheduler / Consumer", "Consume mensajes de SQS.\nEnvía emails via AWS SES.\nRegistra estado de envío.")
}

' ── Sistemas Externos ────────────────────────────────────────
System_Ext(aws_sqs, "AWS SQS", "Cola: credit-evaluation-notifications\nDLQ: credit-eval-notif-dlq")
System_Ext(aws_ses, "AWS SES", "Envío de emails\ntransaccionales.")

' ── Relaciones ───────────────────────────────────────────────

' Usuario → Frontend
Rel(analista, frontend, "Usa", "HTTPS / Browser")

' Frontend → Orquestador
Rel(frontend, orchestrator, "API calls", "HTTPS / REST / JSON\n(Bearer JWT)")

' Orquestador → Riesgos (llamadas paralelas)
Rel(orchestrator, risk_service, "GET /v1/risk/score/{cedula}\nGET /v1/risk/debts/{cedula}", "HTTP REST\n(llamadas paralelas vía\nMicroProfile REST Client)")

' Orquestador → PostgreSQL
Rel(orchestrator, postgres, "Persiste evaluaciones,\nusuarios, roles", "JDBC / Hibernate ORM Panache")

' Orquestador → SQS (publish)
Rel(orchestrator, aws_sqs, "Publica EvaluacionCompletada", "AWS SDK v2 / HTTPS")

' Worker → SQS (consume)
Rel(notif_worker, aws_sqs, "Consume mensajes (polling)", "AWS SDK v2 / HTTPS")

' Worker → PostgreSQL
Rel(notif_worker, postgres, "Actualiza estado\nde notificación", "JDBC / Hibernate ORM Panache")

' Worker → SES
Rel(notif_worker, aws_ses, "Envía email al solicitante", "AWS SDK v2 / HTTPS")

' SES → Solicitante
Rel(aws_ses, solicitante, "Entrega email", "SMTP")

@enduml
```

---

## Diagrama ASCII — Vista de Contenedores

```
 ┌──────────────────────────────────────────────────────────────────────────┐
 │                   SISTEMA DE EVALUACIÓN DE CRÉDITOS                      │
 │                                                                          │
 │  ┌────────────────┐    HTTPS/REST     ┌────────────────────────────────┐ │
 │  │                │    (JWT Bearer)   │                                │ │
 │  │  Frontend SPA  │ ───────────────>  │   Microservicio A              │ │
 │  │  React 18 +    │ <───────────────  │   Orquestador                  │ │
 │  │  TypeScript    │                   │   (Quarkus 3.x)                │ │
 │  │                │                   │                                │ │
 │  │  :3000         │                   │   • POST /v1/credit-evaluations│ │
 │  └────────────────┘                   │   • GET  /v1/credit-evaluations│ │
 │                                       │   • POST /v1/auth/login        │ │
 │                                       │   • POST /v1/auth/users        │ │
 │                                       │   :8080                        │ │
 │                                       └──────┬─────────────┬───────────┘ │
 │                                              │             │             │
 │                              HTTP REST       │             │ JDBC        │
 │                              (paralelo)      │             │             │
 │                                              ▼             ▼             │
 │  ┌────────────────────────────┐   ┌──────────────────────────────────┐  │
 │  │  Microservicio B           │   │         PostgreSQL 16             │  │
 │  │  Riesgos Mock              │   │                                  │  │
 │  │  (Quarkus 3.x)             │   │  • credit_evaluations            │  │
 │  │                            │   │  • users                         │  │
 │  │  • GET /v1/risk/score/{c}  │   │  • roles                         │  │
 │  │  • GET /v1/risk/debts/{c}  │   │  • user_roles                    │  │
 │  │  :8081                     │   │  • notifications                 │  │
 │  └────────────────────────────┘   └──────────────────────────────────┘  │
 │                                              ▲                           │
 │                                              │ JDBC                      │
 │                                   ┌──────────┴───────────┐              │
 │                                   │  Notification Worker  │              │
 │                                   │  (Quarkus Scheduler)  │              │
 │                                   └──────────┬────────────┘              │
 └──────────────────────────────────────────────┼────────────────────────── ┘
                                                │ AWS SDK (consume/publish)
                          ┌─────────────────────┼──────────────────────┐
                          │         AWS          │                      │
                          │                      ▼                      │
                          │              ┌───────────────┐              │
                          │              │   AWS SQS     │              │
                          │              │  (cola msgs)  │              │
                          │              └───────────────┘              │
                          │                                             │
                          │    ┌─────────────────────────────────┐     │
                          │    │           AWS SES               │     │
                          │    │   (envío de emails)             │     │
                          │    └────────────────────────────────-┘     │
                          └─────────────────────────────────────────────┘
```

---

## Contenedores — Tabla Detallada

| Contenedor | Tecnología | Puerto | Responsabilidad Principal |
|------------|-----------|--------|--------------------------|
| Frontend SPA | React 18, TypeScript, Axios | 3000 | UI de evaluación, login, listado |
| Microservicio A — Orquestador | Java 21, Quarkus 3.x, Hibernate Panache | 8080 | API principal, validación, reglas, auth |
| Microservicio B — Riesgos Mock | Java 21, Quarkus 3.x | 8081 | Score y deudas aleatorios por cédula |
| Base de Datos | PostgreSQL 16 | 5432 | Persistencia de evaluaciones, usuarios, notificaciones |
| Notification Worker | Quarkus Scheduler (embebido en MS-A o separado) | — | Polling SQS + envío email |
| AWS SQS | AWS Managed | — | Desacoplamiento async de notificaciones |
| AWS SES | AWS Managed | — | Envío de emails transaccionales |

---

## Protocolos de Comunicación — Justificación

### Frontend ↔ Microservicio A: REST + JSON sobre HTTPS
- Simplicidad de consumo desde el navegador
- JSON nativo en JavaScript/TypeScript
- Autenticación Bearer JWT en header `Authorization`

### Microservicio A ↔ Microservicio B: REST sobre HTTP (interna)
- Las llamadas son **síncronas y paralelas** usando MicroProfile REST Client con `@RegisterRestClient`
- Se lanzan en paralelo (`CompletableFuture` / `Uni.zip`) para reducir latencia total:
  - Sin paralelismo: 2s (score) + 1.5s (deudas) = 3.5s
  - Con paralelismo: max(2s, 1.5s) = **2s** (ahorro del 43%)
- Se configuran timeouts de 5s y Circuit Breaker vía SmallRye Fault Tolerance

> **¿Por qué no gRPC aquí?** Ver [ADR-002](./09-adr.md#adr-002-rest-vs-grpc-para-comunicacion-a-b)

### Microservicio A → AWS SQS: AWS SDK v2
- Publicación asíncrona fire-and-forget post-evaluación
- No bloquea el tiempo de respuesta al cliente
- Retry automático con backoff incluido en el SDK

### Notification Worker → AWS SQS: Long Polling
- Pull de hasta 10 mensajes cada 20 segundos (`WaitTimeSeconds=20`)
- Visibility timeout de 60s para procesamiento seguro
- Delete message solo al confirmar envío exitoso (at-least-once)

---

## Despliegue con Docker Compose (desarrollo local)

```yaml
# Servicios que se ejecutan localmente
services:
  frontend:         # React :3000
  orchestrator:     # Quarkus MS-A :8080
  risk-service:     # Quarkus MS-B :8081
  postgres:         # PostgreSQL :5432
  localstack:       # Emulación local de SQS y SES (LocalStack)
```

> En producción, SQS y SES son servicios AWS reales. LocalStack permite desarrollo offline.
