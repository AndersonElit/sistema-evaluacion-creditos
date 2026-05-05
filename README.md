# Sistema de Evaluación de Créditos

Mini-ecosistema de evaluación de créditos compuesto por un Frontend React, tres microservicios Java Quarkus (`ms-credit-evaluation` orquestador, `ms-risk` riesgos mock, `ms-notifications` notificaciones asíncronas) y **Keycloak** como proveedor de identidad OIDC.

---

## Scaffold — Crear estructura base de microservicios

`scaffold/MavenHexagonalScaffold.java` es la herramienta oficial para generar la estructura base de cada microservicio del sistema. Produce un proyecto Maven multimódulo con **arquitectura hexagonal**, **Quarkus Reactivo + Mutiny**, **PostgreSQL reactivo**, autenticación por token contra **Keycloak** (OIDC) y, opcionalmente, integración con **AWS SQS**.

### Prerrequisitos

| Herramienta | Versión mínima |
|-------------|---------------|
| [jbang](https://www.jbang.dev/download/) | 0.115+ |
| Java | 17+ |

Verificar instalación:

```bash
jbang --version
java --version
```

### Uso

```bash
jbang scaffold/MavenHexagonalScaffold.java [opciones]
```

#### Opciones

| Opción | Alias | Descripción | Requerido | Por defecto |
|--------|-------|-------------|-----------|-------------|
| `--service-name` | `-n` | Nombre del microservicio (usado en groupId, artifactId y directorio raíz) | Sí | `mi-microservicio` |
| `--messaging-system` | `-m` | Integración con SQS: `sqs-producer`, `sqs-consumer`, `none` | No | `none` |
| `--help` | `-h` | Muestra la ayuda | — | — |

### Ejemplos

**Microservicio base (sin mensajería):**
```bash
jbang scaffold/MavenHexagonalScaffold.java -n ms-credit-evaluation
```

**Microservicio con productor SQS** (publica mensajes hacia una cola):
```bash
jbang scaffold/MavenHexagonalScaffold.java -n ms-credit-evaluation -m sqs-producer
```

**Microservicio con consumidor SQS** (recibe mensajes desde una cola):
```bash
jbang scaffold/MavenHexagonalScaffold.java -n ms-notifications -m sqs-consumer
```

### Estructura generada

```
{service-name}/
├── pom.xml                                        ← Parent POM (Quarkus BOM + Quarkiverse AWS BOM)
├── .env                                           ← Variables de entorno (NO commitear)
├── .env.example                                   ← Plantilla de variables de entorno
├── .gitignore
│
├── domain/
│   └── model/                                     ← Entidades, Value Objects, puertos (interfaces)
│
├── application/
│   └── use-cases/                                 ← Casos de uso con Mutiny (Uni<T>)
│
├── infrastructure/
│   ├── driven-adapters/
│   │   ├── postgres/                              ← Hibernate Reactive Panache + reactive-pg-client
│   │   └── sqs-producer/          [opcional -m sqs-producer]
│   │       └── SqsMessagePublisher.java
│   │
│   └── entry-points/
│       ├── rest-api/                              ← JAX-RS Reactive (RESTEasy Reactive + Jackson)
│       │   └── HelloResource.java
│       ├── app/                                   ← Módulo ejecutable: Quarkus main, BeanConfig, application.properties
│       │   ├── MainApplication.java
│       │   ├── BeanConfig.java
│       │   └── src/main/resources/application.properties
│       └── sqs-consumer/          [opcional -m sqs-consumer]
│           └── SqsMessageConsumer.java            ← Polling reactivo con @Scheduled + Uni<Void>
```

### Configuración post-generación

Copiar `.env.example` a `.env` y completar los valores:

```bash
cp .env.example .env
```

Variables a configurar:

```dotenv
# Server
SERVER_PORT=8080

# PostgreSQL Reactive
DB_REACTIVE_URL=postgresql://localhost:5432/mydb
DB_USERNAME=postgres
DB_PASSWORD=password

# Keycloak — validación de tokens Bearer
KEYCLOAK_URL=http://localhost:8180
KEYCLOAK_REALM=banco
KEYCLOAK_CLIENT_ID=ms-credit-evaluation

# AWS SQS (solo si se usó -m sqs-producer o -m sqs-consumer)
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=your-access-key
AWS_SECRET_ACCESS_KEY=your-secret-key
SQS_QUEUE_URL=https://sqs.us-east-1.amazonaws.com/123456789/my-queue
# Para desarrollo local con LocalStack:
# SQS_ENDPOINT_URL=http://localhost:4566
```

### Levantar el microservicio en modo desarrollo

```bash
cd {service-name}/infrastructure/entry-points/app
mvn quarkus:dev
```

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

## Stack Tecnológico

| Capa | Tecnología |
|------|-----------|
| Frontend | React 18, TypeScript, Axios, Keycloak JS Adapter |
| ms-credit-evaluation | Java 21, Quarkus 3.x, Hibernate Reactive Panache |
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

---

## Documentación detallada

| # | Documento | Descripción |
|---|-----------|-------------|
| 01 | [DDD Estratégico](./docs/01-ddd-estrategico.md) | Bounded Contexts, Context Map, Lenguaje Ubicuo, Eventos de Dominio |
| 02 | [BDD — Escenarios](./docs/02-bdd-scenarios.md) | Especificaciones en Gherkin para funcionalidades y seguridad |
| 03 | [C4 — Contexto](./docs/03-c4-context.md) | Diagrama de nivel de Sistema (C4 Level 1) |
| 04 | [C4 — Contenedores](./docs/04-c4-container.md) | Diagrama de nivel de Contenedores (C4 Level 2) |
| 05 | [OpenAPI / Swagger](./docs/05-openapi-swagger.md) | Especificación completa de todos los endpoints REST |
| 06 | [Modelo de Base de Datos](./docs/06-db-model.md) | Esquema PostgreSQL, DDL y diagrama ER |
| 07 | [Autenticación y Login](./docs/07-auth-login.md) | Keycloak OIDC/PKCE, configuración de Realm, integración Quarkus y Frontend |
| 08 | [Notificaciones SQS](./docs/08-notifications-sqs.md) | Flujo de notificaciones por email via AWS SQS/SES |
| 09 | [ADR — Decisiones de Arquitectura](./docs/09-adr.md) | REST vs gRPC, Keycloak, ms-notifications y otras decisiones clave |
| 10 | [SDD — Security-Driven Development](./docs/10-sdd.md) | Threat Model STRIDE, controles, security requirements, testing y pipeline |
