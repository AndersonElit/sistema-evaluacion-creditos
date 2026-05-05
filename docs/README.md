# Sistema de Evaluación de Créditos — Documentación de Planificación y Diseño

## Descripción General

Mini-ecosistema de evaluación de créditos compuesto por un Frontend React y cuatro microservicios Java Quarkus: `ms-credit-evaluation` (orquestador), `ms-risk` (riesgos mock), `ms-auth` (identidad) y `ms-notifications` (notificaciones asíncronas por email vía AWS SQS/SES).

---

## Índice de Documentación

| # | Documento | Descripción |
|---|-----------|-------------|
| 01 | [DDD Estratégico](./01-ddd-estrategico.md) | Bounded Contexts, Context Map, Lenguaje Ubicuo, Eventos de Dominio |
| 02 | [BDD — Escenarios](./02-bdd-scenarios.md) | Especificaciones en Gherkin para todas las funcionalidades |
| 03 | [C4 — Contexto](./03-c4-context.md) | Diagrama de nivel de Sistema (C4 Level 1) |
| 04 | [C4 — Contenedores](./04-c4-container.md) | Diagrama de nivel de Contenedores (C4 Level 2) |
| 05 | [OpenAPI / Swagger](./05-openapi-swagger.md) | Especificación completa de todos los endpoints REST |
| 06 | [Modelo de Base de Datos](./06-db-model.md) | Esquema PostgreSQL, DDL y diagrama ER |
| 07 | [Autenticación y Login](./07-auth-login.md) | Diseño de Auth JWT, gestión de usuarios y roles |
| 08 | [Notificaciones SQS](./08-notifications-sqs.md) | Flujo de notificaciones por email via AWS SQS/SES |
| 09 | [ADR — Decisiones de Arquitectura](./09-adr.md) | REST vs gRPC, tech stack y otras decisiones clave |

---

## Stack Tecnológico Resumido

| Capa | Tecnología |
|------|-----------|
| Frontend | React 18, TypeScript, Axios |
| ms-credit-evaluation | Java 21, Quarkus 3.x, Hibernate ORM Panache |
| ms-risk | Java 21, Quarkus 3.x |
| ms-auth | Java 21, Quarkus 3.x, SmallRye JWT Build |
| ms-notifications | Java 21, Quarkus 3.x, AWS SDK v2 |
| Base de Datos (ms-credit-evaluation) | PostgreSQL 16 — `creditos_db` |
| Base de Datos (ms-auth) | PostgreSQL 16 — `auth_db` |
| Base de Datos (ms-notifications) | PostgreSQL 16 — `notifications_db` |
| Mensajería | AWS SQS + AWS SES |
| Comunicación ms-credit-evaluation ↔ ms-risk | REST (HTTP/1.1) + MicroProfile REST Client |
| Comunicación ms-credit-evaluation ↔ ms-auth | Ninguna en runtime — JWT validado con clave pública compartida |
| Comunicación ms-credit-evaluation → ms-notifications | Asíncrona vía AWS SQS (fire-and-forget) |
| Contenedores | Docker + Docker Compose |

---

## Arquitectura en una Línea

```
[React UI] ──REST──> [ms-auth :8082] ──JWT──> [React UI]
                                                    │
[React UI] ──REST+JWT──> [ms-credit-evaluation :8080] ──REST──> [ms-risk :8081]
                                    │                                
                               [creditos_db]                     
                                    │                                
                               [AWS SQS] ──consume──> [ms-notifications :8083]
                                                               │          │
                                                         [AWS SES]  [notifications_db]

[ms-auth] ──escribe──> [auth_db]
```

---

## Funcionalidades del Sistema

### ms-auth (nuevo, desacoplado)
- Servicio independiente dedicado a identidad y acceso
- JWT stateless firmado con RS256 (SmallRye JWT Build)
- Registro de nuevos usuarios (solo por `ADMIN`)
- Roles: `ADMIN`, `ANALYST`, `VIEWER`
- Base de datos propia: `auth_db`
- ms-credit-evaluation valida JWT usando la clave pública de ms-auth sin llamarlo en runtime

### ms-credit-evaluation (simplificado)
- Solo responsabilidad: orquestar evaluaciones de crédito
- Valida JWT con la clave pública compartida de ms-auth
- Sin gestión de usuarios ni emisión de tokens
- Base de datos propia: `creditos_db`

### ms-notifications (desacoplado)
- Microservicio independiente dedicado a notificaciones por email
- Consume mensajes de AWS SQS de forma autónoma (Quarkus Scheduler)
- Envía emails vía AWS SES con el resultado de la evaluación (APROBADO / RECHAZADO)
- Gestiona el estado de cada notificación en su propia base de datos (`notifications_db`)
- Dead Letter Queue (DLQ) para mensajes fallidos tras 3 intentos
- `ms-credit-evaluation` solo publica en SQS — no conoce a `ms-notifications`
