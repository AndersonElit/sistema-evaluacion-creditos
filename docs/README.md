# Sistema de Evaluación de Créditos — Documentación de Planificación y Diseño

## Descripción General

Mini-ecosistema de evaluación de créditos compuesto por un Frontend React, un Microservicio Orquestador (A), un Microservicio de Riesgos Mock (B), un módulo de Autenticación/Autorización y un sistema de Notificaciones por correo vía AWS SQS. Todo el backend en **Java Quarkus**.

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
| Microservicio A (Orquestador) | Java 21, Quarkus 3.x, Hibernate ORM Panache |
| Microservicio B (Riesgos) | Java 21, Quarkus 3.x |
| Auth | Quarkus Security + SmallRye JWT |
| Base de Datos | PostgreSQL 16 |
| Mensajería | AWS SQS + AWS SES |
| Comunicación A↔B | REST (HTTP/1.1) + MicroProfile REST Client |
| Contenedores | Docker + Docker Compose |

---

## Arquitectura en una Línea

```
[React UI] ──REST──> [Orquestador A] ──REST──> [Riesgos B]
                          │                          
                     [PostgreSQL]              
                          │                          
                     [AWS SQS] ──consume──> [Notification Worker]
                                                     │
                                               [AWS SES / Email]
```

---

## Nuevas Funcionalidades Incluidas

### Login y Gestión de Usuarios
- JWT stateless con SmallRye JWT en Quarkus
- Registro de nuevos usuarios (solo por `ADMIN`)
- Roles: `ADMIN`, `ANALYST`, `VIEWER`
- Endpoints protegidos por rol vía `@RolesAllowed`

### Notificaciones por Email (SQS)
- Al completar una evaluación, Microservicio A publica un mensaje en una cola SQS
- Un worker (Quarkus scheduler) consume la cola y envía email vía AWS SES
- Notifica al solicitante: crédito **APROBADO** o **RECHAZADO**
- Dead Letter Queue (DLQ) para mensajes fallidos
