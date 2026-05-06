# Sistema de Evaluación de Créditos

Sistema bancario que determina si una persona es sujeta de crédito en función de su perfil de riesgo y capacidad de pago. Compuesto por un Frontend React, tres microservicios Java Quarkus y **Keycloak** como proveedor de identidad OIDC, diseñado con **DDD**, **arquitectura hexagonal**, **programación reactiva (Mutiny)** y **Security-Driven Development (SDD)**.

---

## Propósito y Dominio

El **Core Domain** es la evaluación de crédito: orquestar el análisis de riesgo, aplicar las reglas de negocio y persistir el resultado. Dos dominios de soporte lo complementan sin acoplarse a él.

| Subdominio | Tipo | Implementación |
|------------|------|----------------|
| Evaluación de Crédito | **Core Domain** | `ms-credit-evaluation :8080` |
| Valoración de Riesgos | Supporting Domain | `ms-risk :8081` (mock) |
| Notificaciones | Supporting Domain | `ms-notifications :8083` |
| Identidad y Acceso | Generic Domain | Keycloak 24.x `:9000` — realm `banco` |

### Regla de aprobación

```
score > 70  AND  (deudaMensual + cuotaNueva) < salario × 0.40
```

- **score**: obtenido de `ms-risk` (0–100)
- **capacidad de pago**: el solicitante no puede comprometer más del 40 % de su salario mensual
- **cédula**: validada con algoritmo Módulo 10 ecuatoriano antes de consultar `ms-risk`

---

## Actores

| Actor | Rol en Keycloak | Puede evaluar | Puede consultar | Gestiona usuarios |
|-------|----------------|:---:|:---:|:---:|
| Analista de Crédito | `ANALYST` | ✅ | ✅ | ❌ |
| Administrador | `ADMIN` | ✅ | ✅ | ✅ (Admin Console) |
| Viewer | `VIEWER` | ❌ | ✅ | ❌ |
| Solicitante | — (externo) | ❌ | ❌ | recibe email |

> El solicitante no opera el sistema; el analista ingresa los datos en su nombre y el resultado llega por email vía AWS SES.

---

## Flujo de Evaluación

```
1. Analista inicia sesión    → Keycloak emite JWT (RS256, Authorization Code + PKCE)
2. Frontend envía solicitud  → POST /v1/credit-evaluations  [Bearer JWT]
3. ms-credit-evaluation      → valida JWT contra JWKS de Keycloak (sin llamada síncrona runtime)
4. Llamadas paralelas        → GET /score/{cedula}  +  GET /deudas/{cedula}  [ms-risk, ~2s]
5. Regla de negocio          → APROBADO si score > 70 AND capacidad de pago < 40%
6. Persistencia              → creditos_db (PostgreSQL)
7. Publicación asíncrona     → AWS SQS (fire-and-forget, ms-credit-evaluation no conoce ms-notifications)
8. ms-notifications (polling → cada 5s) consume SQS → envía email vía AWS SES → actualiza notifications_db
```

Las llamadas a `ms-risk` se ejecutan **en paralelo con Mutiny** (`Uni.combine().all()`), reduciendo la latencia de 3.5 s a ~2 s (−43 %).

---

## Fuera del Alcance

El sistema **no** incluye:
- Consulta a burós de crédito reales (se usa mock en `ms-risk`)
- Portal de autoservicio para el solicitante
- Integración con core bancario
- SSO / federación LDAP (Keycloak lo soporta, pero no está configurado)

---

## Scaffold — Crear estructura base de microservicios

`scaffold/MavenHexagonalScaffold.java` (v2.1) es la herramienta oficial para generar la estructura base de cada microservicio del sistema. Produce un proyecto Maven multimódulo con **arquitectura hexagonal**, **Quarkus 3.17.4 Reactivo + Mutiny**, **PostgreSQL reactivo**, autenticación por token contra **Keycloak** (OIDC), **SmallRye JWT**, **Hibernate Validator**, **SmallRye Health** y, opcionalmente, integración con **AWS SQS**. Incluye dependencias de test (JUnit 5, Mockito, AssertJ, REST-Assured) y expone **Swagger UI** en modo dev (`/swagger-ui`).

### Prerrequisitos

| Herramienta | Versión mínima |
|-------------|---------------|
| [jbang](https://www.jbang.dev/download/) | 0.115+ |
| Java | 21+ |

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
│   └── model/
│       └── com.{svc}.model.{entity,port,valueobject}/   ← Entidades, Value Objects, puertos
│
├── application/
│   └── use-cases/
│       └── com.{svc}.usecases.{command,result,exception}/  ← Casos de uso (Uni<T>)
│
├── infrastructure/
│   ├── driven-adapters/
│   │   ├── postgres/
│   │   │   └── com.{svc}.postgres.{entity,repository,mapper}/  ← Hibernate Reactive Panache
│   │   └── sqs-producer/          [opcional -m sqs-producer]
│   │       └── com.{svc}.sqsproducer.adapter/
│   │           └── SqsMessagePublisher.java
│   │
│   └── entry-points/
│       ├── rest-api/
│       │   └── com.{svc}.restapi.{dto,resource,exception,mapper}/
│       │       └── resource/HelloResource.java    ← JAX-RS Reactive + Hibernate Validator
│       ├── app/                                   ← Módulo ejecutable: Quarkus main, BeanConfig, application.properties
│       │   ├── src/main/resources/application.properties
│       │   └── src/main/java/com/{servicename}/
│       │       ├── MainApplication.java
│       │       └── BeanConfig.java
│       └── sqs-consumer/          [opcional -m sqs-consumer]
│           └── com.{svc}.sqsconsumer.adapter/
│               └── SqsMessageConsumer.java        ← Polling reactivo con @Scheduled + Uni<Void>
```

La versión 2.1 genera automáticamente los sub-paquetes por capa (`entity`, `port`, `valueobject`, `command`, `result`, `mapper`, etc.) mediante el método `createSubPackages`, evitando crearlos a mano.

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
| ms-credit-evaluation | Java 21, Quarkus 3.17.4, Hibernate Reactive Panache |
| ms-risk | Java 21, Quarkus 3.17.4 |
| ms-notifications | Java 21, Quarkus 3.17.4, AWS SDK v2 |
| IAM / Autenticación | Keycloak 24.x (OIDC + OAuth2) |
| Base de Datos (ms-credit-evaluation) | PostgreSQL 16 — `creditos_db` |
| Base de Datos (ms-notifications) | PostgreSQL 16 — `notifications_db` |
| Base de Datos (Keycloak) | PostgreSQL 16 — `keycloak_db` (gestionada por Keycloak) |
| Mensajería | AWS SQS + AWS SES |
| Comunicación ms-credit-evaluation ↔ ms-risk | REST (HTTP/1.1) + MicroProfile REST Client |
| Autenticación ms-credit-evaluation | JWT validado contra JWKS de Keycloak (sin llamadas runtime síncronas) |
| Comunicación ms-credit-evaluation → ms-notifications | Asíncrona vía AWS SQS (fire-and-forget) |
| Contenedores | Docker + Docker Compose |
| CI/CD — Secret Scanning | GitLeaks |
| CI/CD — SAST | SonarQube / SonarCloud |
| CI/CD — Dependency Check | OWASP Dependency Check (failBuildOnCVSS=7) |
| CI/CD — Image Scan | Trivy (severity: HIGH, CRITICAL) |
| Automatización CI/CD | GitHub Actions |

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

## Inicio Rápido

### Modo desarrollo (infraestructura en Docker, microservicios locales)

```bash
# 1. Levantar PostgreSQL × 3, Keycloak y LocalStack
docker compose -f docker-compose.infra.yml up -d

# 2. Arrancar cada microservicio en su propia terminal
cd backend/ms-risk/infrastructure/entry-points/app && mvn quarkus:dev
cd backend/ms-credit-evaluation/infrastructure/entry-points/app && mvn quarkus:dev
cd backend/ms-notifications/infrastructure/entry-points/app && mvn quarkus:dev

# 3. Frontend (Vite dev server con HMR)
cd frontend && npm run dev

# Swagger UI disponible en modo dev
# ms-credit-evaluation: http://localhost:8080/swagger-ui
# ms-risk:              http://localhost:8081/swagger-ui
```

### Stack completo con Docker Compose

El `docker-compose.yml` en la raíz construye y levanta los **5 servicios** (3 microservicios Java + frontend + infraestructura) con dependencias y healthchecks coordinados:

```bash
# Build de todas las imágenes y levantar
docker compose build           # primera vez: ~5–10 min
docker compose up -d

# Verificar estado
docker compose ps
curl -s http://localhost:8080/q/health | jq .status   # ms-credit-evaluation: UP
curl -s http://localhost:8081/q/health | jq .status   # ms-risk: UP
curl -s http://localhost:8083/q/health | jq .status   # ms-notifications: UP
curl -s http://localhost:9000/health/ready             # Keycloak: UP
curl -s -o /dev/null -w "%{http_code}" http://localhost:3000  # Frontend: 200

# Teardown
docker compose down        # conserva volúmenes
docker compose down -v     # reset completo
```

**Artefactos Docker:**

| Componente | Dockerfile | Imagen base build / runtime |
|-----------|-----------|----------------------------|
| `ms-risk`, `ms-credit-evaluation`, `ms-notifications` | `backend/<servicio>/Dockerfile` | `maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre` (+ `curl` para healthcheck) |
| `frontend` | `frontend/Dockerfile` + `frontend/nginx.conf` | `node:20-alpine` → `nginx:alpine` (sirve `dist/` en `:3000`, proxy `/api/` → `ms-credit-evaluation:8080`) |

**Configuración Keycloak dentro de Docker (importante):**

El JWT que emite Keycloak lleva como `iss` el host con el que el browser obtuvo el token (`http://localhost:9000`). El backend, en cambio, debe descargar las llaves vía la red interna de Docker. Por eso `ms-credit-evaluation` distingue dos URLs:

| Variable | Propósito | Modo dev | En Docker |
|----------|-----------|----------|-----------|
| `KEYCLOAK_ISSUER_URL` | Validar el claim `iss` del JWT | `http://localhost:9000` | `http://localhost:9000` |
| `KEYCLOAK_INTERNAL_URL` | Descargar las llaves públicas (JWKS) | `http://localhost:9000` | `http://keycloak:8080` |

**Puertos del sistema:**

| Servicio | Puerto |
|----------|--------|
| Frontend (React) | `3000` |
| ms-credit-evaluation | `8080` |
| ms-risk | `8081` |
| ms-notifications | `8083` |
| Keycloak Admin Console | `9000` |
| LocalStack (SQS + SES) | `4566` |
| PostgreSQL — creditos_db | `5432` |
| PostgreSQL — notifications_db | `5434` |
| PostgreSQL — keycloak_db | `5435` |

---

## CI/CD Security Pipeline

El pipeline de seguridad se define en `.github/workflows/security.yml` y bloquea PRs que no superen todos los gates.

| Job | Herramienta | Condición de bloqueo |
|-----|-------------|----------------------|
| Secret Scanning | **GitLeaks** | Cualquier secreto detectado en el diff |
| SAST | **SonarQube** | Quality Gate fallido o issue de seguridad nuevo |
| Dependency Vulnerabilities | **OWASP Dependency Check** | CVE con CVSS ≥ 7.0 sin supresión justificada |
| Docker Image Scan | **Trivy** (×3 servicios) | Vulnerabilidad CRITICAL en imagen |

El pipeline se activa en cada push a `main`/`develop` y en todo PR hacia `main`. Los gates están reforzados mediante **Branch Protection Rules** en GitHub (require status checks + 1 approver).

### Pre-commit hook local (GitLeaks)

```bash
# Instalar GitLeaks (Linux)
curl -sSfL https://raw.githubusercontent.com/gitleaks/gitleaks/main/scripts/install.sh | sh -s -- -b /usr/local/bin

# Registrar hook
cat > .git/hooks/pre-commit << 'EOF'
#!/bin/sh
gitleaks protect --staged --verbose
EOF
chmod +x .git/hooks/pre-commit
```

### Security Acceptance Criteria (SDD)

Una funcionalidad está **done** desde la perspectiva de seguridad cuando:

- Escenarios BDD de seguridad pasan: sin JWT → 401, rol incorrecto → 403, input inválido → 422
- No existen CVEs CVSS ≥ 7 sin suprimir en las dependencias del servicio modificado
- El análisis SAST no introduce nuevos issues tipo Bug o Vulnerability
- No hay secretos en el diff del PR (GitLeaks pasa sin alertas)
- El endpoint nuevo tiene `@RolesAllowed` o `@Authenticated` explícito y `@Valid` en el input
- La imagen Docker no tiene vulnerabilidades CRITICAL

---

## Plan de Desarrollo

Implementación paso a paso del sistema completo. Cada paso tiene prerrequisitos, código completo y criterios de verificación.

| # | Paso | Descripción |
|---|------|-------------|
| 01 | [Infraestructura Local](./development-plan/01-infraestructura-local.md) ✓ | `docker-compose.infra.yml`: PostgreSQL ×3, Keycloak y LocalStack |
| 02 | [Keycloak Realm](./development-plan/02-keycloak-realm.md) ✓ | Configuración del realm `banco`, clientes OIDC, roles y usuarios de prueba |
| 03 | [ms-risk](./development-plan/03-ms-risk.md) ✓ | Mock REST: `GET /v1/risk/score/{cedula}` y `GET /v1/risk/debts/{cedula}` |
| 04 | [ms-credit-evaluation — Dominio](./development-plan/04-ms-credit-evaluation-dominio.md) ✓ | Value Objects (`Cedula`, `Dinero`), Agregado raíz, Puertos + tests JUnit puro |
| 05 | [ms-credit-evaluation — BD](./development-plan/05-ms-credit-evaluation-bd.md) ✓ | Migraciones Flyway, entidad Panache, repositorio reactivo |
| 06 | [ms-credit-evaluation — Caso de Uso](./development-plan/06-ms-credit-evaluation-usecase.md) | `EvaluarCreditoUseCase` con llamadas paralelas Mutiny + tests Mockito |
| 07 | [ms-credit-evaluation — API REST](./development-plan/07-ms-credit-evaluation-api.md) | `CreditEvaluationResource`, DTOs con `@Valid`, manejo de errores, tests REST-Assured |
| 08 | [ms-notifications](./development-plan/08-ms-notifications.md) ✓ | SQS consumer, AWS SES, idempotencia, DLQ |
| 09 | [LocalStack SQS + SES](./development-plan/09-localstack-sqs-ses.md) | Colas (`credit-evaluation-notifications`, DLQ) y verificación de emails |
| 10 | [Frontend React](./development-plan/10-frontend-react.md) ✓ | Keycloak JS Adapter, formulario de evaluación, tabla de resultados |
| 11 | [Docker Compose Completo](./development-plan/11-docker-compose-completo.md) ✓ | Dockerfiles multistage para los 3 microservicios y el frontend; `docker-compose.yml` unificado en la raíz |
| 12 | [CI/CD Security Pipeline](./development-plan/12-cicd-security-pipeline.md) | GitHub Actions: GitLeaks, SonarQube, OWASP, Trivy + branch protection rules |
| 13 | [Verificación End-to-End](./development-plan/13-verificacion-end-to-end.md) | Happy path, controles de acceso, inyección SQL, resiliencia, idempotencia |

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
