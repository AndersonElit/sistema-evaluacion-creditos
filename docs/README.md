# Sistema de Evaluación de Créditos — Documentación de Planificación y Diseño

## Descripción General

Mini-ecosistema de evaluación de créditos compuesto por un Frontend React, tres microservicios Java Quarkus (`ms-credit-evaluation` orquestador, `ms-risk` riesgos mock, `ms-notifications` notificaciones asíncronas) y **Keycloak** como proveedor de identidad OIDC.

---

## Índice de Documentación

| # | Documento | Descripción |
|---|-----------|-------------|
| 01 | [DDD Estratégico](./01-ddd-estrategico.md) | Bounded Contexts, Context Map, Lenguaje Ubicuo, Eventos de Dominio |
| 02 | [BDD — Escenarios](./02-bdd-scenarios.md) | Especificaciones en Gherkin para funcionalidades y seguridad |
| 03 | [C4 — Contexto](./03-c4-context.md) | Diagrama de nivel de Sistema (C4 Level 1) |
| 04 | [C4 — Contenedores](./04-c4-container.md) | Diagrama de nivel de Contenedores (C4 Level 2) |
| 05 | [OpenAPI / Swagger](./05-openapi-swagger.md) | Especificación completa de todos los endpoints REST |
| 06 | [Modelo de Base de Datos](./06-db-model.md) | Esquema PostgreSQL, DDL y diagrama ER |
| 07 | [Autenticación y Login](./07-auth-login.md) | Keycloak OIDC/PKCE, configuración de Realm, integración Quarkus y Frontend |
| 08 | [Notificaciones SQS](./08-notifications-sqs.md) | Flujo de notificaciones por email via AWS SQS/SES |
| 09 | [ADR — Decisiones de Arquitectura](./09-adr.md) | REST vs gRPC, Keycloak, ms-notifications y otras decisiones clave |
| 10 | [SDD — Security-Driven Development](./10-sdd.md) | Threat Model STRIDE, controles, security requirements, testing y pipeline |

---

## Stack Tecnológico Resumido

| Capa | Tecnología |
|------|-----------|
| Frontend | React 18, TypeScript, Axios, Keycloak JS Adapter |
| ms-credit-evaluation | Java 21, Quarkus 3.x, Hibernate ORM Panache |
| ms-risk | Java 21, Quarkus 3.x |
| ms-notifications | Java 21, Quarkus 3.x, AWS SDK v2 |
| IAM / Autenticación | Keycloak 24.x (OIDC + OAuth2) |
| Base de Datos (ms-credit-evaluation) | PostgreSQL 16 — `creditos_db` |
| Base de Datos (ms-notifications) | PostgreSQL 16 — `notifications_db` |
| Base de Datos (Keycloak) | PostgreSQL 16 — `keycloak_db` (gestionada por Keycloak) |
| Mensajería | AWS SQS + AWS SES |
| Comunicación ms-credit-evaluation ↔ ms-risk | REST (HTTP/1.1) + MicroProfile REST Client |
| Autenticación ms-credit-evaluation | JWT validado contra JWKS de Keycloak (sin llamadas runtime síncronas) |
| Comunicación ms-credit-evaluation → ms-notifications | Asíncrona vía AWS SQS (fire-and-forget) |
| Contenedores | Docker + Docker Compose |

---

## Arquitectura en una Línea

```
[React UI] ──OIDC/PKCE──> [Keycloak :9000] ──JWT──> [React UI]
                                                           │
[React UI] ──REST+JWT──> [ms-credit-evaluation :8080] ──REST──> [ms-risk :8081]
                                    │
                               [creditos_db]
                                    │
                               [AWS SQS] ──consume──> [ms-notifications :8083]
                                                               │          │
                                                         [AWS SES]  [notifications_db]

[Keycloak] ──escribe──> [keycloak_db]
[ms-credit-evaluation valida JWT contra JWKS de Keycloak]
```

---

## Funcionalidades del Sistema

### Keycloak (IAM — Identity and Access Management)
- Proveedor de identidad centralizado (OIDC / OAuth2)
- Flujo de login: Authorization Code + PKCE desde el Frontend
- Emite JWT firmados con RS256; ms-credit-evaluation los valida contra el JWKS endpoint de Keycloak
- Gestión de usuarios, credenciales y roles vía Keycloak Admin Console o Admin REST API
- Roles del realm: `ADMIN`, `ANALYST`, `VIEWER`
- Base de datos propia: `keycloak_db` (gestionada internamente por Keycloak)

### ms-credit-evaluation
- Única responsabilidad: orquestar evaluaciones de crédito
- Valida JWT contra JWKS de Keycloak (`/realms/banco/protocol/openid-connect/certs`)
- Sin gestión de usuarios ni emisión de tokens — todo delegado a Keycloak
- Base de datos propia: `creditos_db`

### ms-notifications (desacoplado)
- Microservicio independiente dedicado a notificaciones por email
- Consume mensajes de AWS SQS de forma autónoma (Quarkus Scheduler)
- Envía emails vía AWS SES con el resultado de la evaluación (APROBADO / RECHAZADO)
- Gestiona el estado de cada notificación en su propia base de datos (`notifications_db`)
- Dead Letter Queue (DLQ) para mensajes fallidos tras 3 intentos
- `ms-credit-evaluation` solo publica en SQS — no conoce a `ms-notifications`
