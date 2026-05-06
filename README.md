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

---

## Guía de Ejecución Local desde Cero

Esta sección está dirigida a cualquier persona que clone el repositorio y quiera levantar el sistema completo en su máquina. Hay dos opciones según el caso de uso.

---

### Prerrequisitos

| Herramienta | Versión mínima | Para qué se usa |
|-------------|----------------|-----------------|
| [Docker Engine](https://docs.docker.com/engine/install/) | 24+ | Contenedores de infraestructura y microservicios |
| [Docker Compose](https://docs.docker.com/compose/install/) | v2 (plugin) | Orquestación del stack completo |
| [JDK 21](https://adoptium.net/) | 21+ | Solo Opción B — compilar y ejecutar microservicios locales |
| [Maven](https://maven.apache.org/download.cgi) | 3.9+ | Solo Opción B — build de microservicios |
| [Node.js](https://nodejs.org/) | 20+ | Solo Opción B — servidor de desarrollo del frontend |

Verificar instalaciones:

```bash
docker --version           # Docker version 24.x.x
docker compose version     # Docker Compose version v2.x.x
java --version             # openjdk 21.x.x  (solo Opción B)
mvn --version              # Apache Maven 3.9.x  (solo Opción B)
node --version             # v20.x.x  (solo Opción B)
```

---

### Opción A — Stack completo con Docker Compose

Todo corre dentro de Docker: infraestructura, tres microservicios y frontend. Es la forma más rápida de ver el sistema funcionando sin instalar Java ni Node.js.

> **Tiempo estimado:** 10–15 min en la primera ejecución (descarga de imágenes base y compilación Maven dentro de los contenedores).

#### Paso 1 — Clonar el repositorio

```bash
git clone <URL-del-repositorio> sistema-evaluacion-creditos
cd sistema-evaluacion-creditos
```

#### Paso 2 — Crear los archivos `.env`

Los archivos `.env` no se incluyen en el repositorio (están en `.gitignore`). El `docker-compose.yml` los requiere para `ms-credit-evaluation` y `ms-notifications`. El resto de la configuración (base de datos, JWT, URLs internas) es inyectada automáticamente por LocalStack vía AWS SSM Parameter Store al arrancar.

```bash
cp backend/ms-credit-evaluation/.env.example backend/ms-credit-evaluation/.env
cp backend/ms-notifications/.env.example     backend/ms-notifications/.env
```

Editar `backend/ms-credit-evaluation/.env` — reemplazar únicamente las líneas marcadas:

```dotenv
SERVER_PORT=8080

# Dejar vacíos — la configuración viene de SSM (LocalStack la carga al iniciar)
DB_REACTIVE_URL=
DB_USERNAME=
DB_PASSWORD=
KEYCLOAK_URL=
KEYCLOAK_REALM=
KEYCLOAK_CLIENT_ID=

# AWS LocalStack — valores fijos para desarrollo local
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
AWS_SSM_ENDPOINT=http://localstack:4566
SSM_PREFIX=/banco/ms-credit-evaluation
```

Editar `backend/ms-notifications/.env` con los mismos valores, cambiando solo `SSM_PREFIX`:

```dotenv
SERVER_PORT=8080

DB_REACTIVE_URL=
DB_USERNAME=
DB_PASSWORD=
KEYCLOAK_URL=
KEYCLOAK_REALM=
KEYCLOAK_CLIENT_ID=

AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
AWS_SSM_ENDPOINT=http://localstack:4566
SSM_PREFIX=/banco/ms-notifications
```

> `ms-risk` no requiere `.env` — su configuración está hardcodeada en `application.properties` ya que es un servicio mock sin credenciales externas.

#### Paso 3 — Construir y levantar el stack

```bash
# Primera vez: construye las imágenes Docker de los 3 microservicios y el frontend
docker compose build

# Levanta todo en segundo plano
docker compose up -d
```

Docker Compose respeta el orden de arranque mediante `depends_on` con healthchecks:

```
PostgreSQL ×3 → LocalStack → Keycloak → ms-risk → ms-credit-evaluation → ms-notifications → frontend
```

#### Paso 4 — Verificar que todo está saludable

```bash
docker compose ps
```

Todos los servicios deben aparecer en estado `healthy` o `running`. Si alguno está en `starting`, esperar unos segundos y repetir. También se puede verificar individualmente:

```bash
# Healthcheck de cada microservicio
curl -s http://localhost:8080/q/health | python3 -m json.tool   # ms-credit-evaluation
curl -s http://localhost:8081/q/health | python3 -m json.tool   # ms-risk
curl -s http://localhost:8083/q/health | python3 -m json.tool   # ms-notifications

# Keycloak
curl -s http://localhost:9000/realms/banco | python3 -m json.tool

# Frontend (debe retornar HTTP 200)
curl -o /dev/null -w "%{http_code}\n" http://localhost:3000

# LocalStack — verificar colas SQS y parámetros SSM
docker exec localstack awslocal sqs list-queues
docker exec localstack awslocal ssm get-parameters-by-path --path "/banco/" --recursive --query 'Parameters[*].Name' --output table
```

---

### Opción B — Modo desarrollo (infraestructura en Docker, código fuente local)

Ideal para modificar el código fuente de algún microservicio y ver los cambios al instante gracias al hot-reload de Quarkus dev mode.

> **Prerrequisitos adicionales:** JDK 21, Maven 3.9+ y Node.js 20+ instalados en el sistema.

#### Paso 1 — Clonar el repositorio

```bash
git clone <URL-del-repositorio> sistema-evaluacion-creditos
cd sistema-evaluacion-creditos
```

#### Paso 2 — Levantar solo la infraestructura

```bash
docker compose -f docker-compose.infra.yml up -d
```

Esto levanta: PostgreSQL ×3 (puertos 5432, 5434, 5435), Keycloak (9000) y LocalStack (4566). Los microservicios y el frontend **no** se inician.

Esperar a que todos estén saludables:

```bash
docker compose -f docker-compose.infra.yml ps
```

#### Paso 3 — Importar el realm de Keycloak

`docker-compose.infra.yml` no importa el realm automáticamente (a diferencia del `docker-compose.yml` completo), por lo que hay que importarlo una sola vez vía la API REST de Keycloak:

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST "http://localhost:9000/admin/realms" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(curl -s \
    -d 'client_id=admin-cli&username=admin&password=admin&grant_type=password' \
    'http://localhost:9000/realms/master/protocol/openid-connect/token' \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')" \
  -d @scripts/keycloak-realm-banco.json
```

Una respuesta `201` indica que el realm `banco` fue importado correctamente.

> Alternativa visual: abrir `http://localhost:9000`, ingresar con `admin / admin`, ir a **Create realm** y subir el archivo `scripts/keycloak-realm-banco.json`.

#### Paso 4 — Actualizar parámetros SSM con rutas de localhost

El script `localstack-init.sh` carga los parámetros SSM con hostnames internos de Docker (`postgres-credits`, `ms-risk`, `keycloak`). Al correr los microservicios localmente esos hostnames no resuelven, por lo que hay que sobreescribirlos con `localhost`:

```bash
docker exec -it localstack python3 << 'EOF'
import boto3
ssm = boto3.client('ssm', endpoint_url='http://localhost:4566',
                   region_name='us-east-1',
                   aws_access_key_id='test', aws_secret_access_key='test')

overrides = [
    ('/banco/ms-credit-evaluation/quarkus.datasource.reactive.url',
     'postgresql://localhost:5432/creditos_db'),
    ('/banco/ms-credit-evaluation/quarkus.rest-client.risk-service.url',
     'http://localhost:8081'),
    ('/banco/ms-credit-evaluation/mp.jwt.verify.publickey.location',
     'http://localhost:9000/realms/banco/protocol/openid-connect/certs'),
    ('/banco/ms-credit-evaluation/mp.jwt.verify.issuer',
     'http://localhost:9000/realms/banco'),
    ('/banco/ms-credit-evaluation/quarkus.sqs.endpoint-override',
     'http://localhost:4566'),
    ('/banco/ms-credit-evaluation/sqs.queue.url',
     'http://localhost:4566/000000000000/credit-evaluation-notifications'),
    ('/banco/ms-notifications/quarkus.datasource.reactive.url',
     'postgresql://localhost:5434/notifications_db'),
    ('/banco/ms-notifications/quarkus.sqs.endpoint-override',
     'http://localhost:4566'),
    ('/banco/ms-notifications/sqs.queue.url',
     'http://localhost:4566/000000000000/credit-evaluation-notifications'),
    ('/banco/ms-notifications/quarkus.ses.endpoint-override',
     'http://localhost:4566'),
]

for name, value in overrides:
    ssm.put_parameter(Name=name, Value=value, Type='String', Overwrite=True)
    print(f'  OK  {name}')
EOF
```

#### Paso 5 — Crear los archivos `.env` para desarrollo local

```bash
cp backend/ms-credit-evaluation/.env.example backend/ms-credit-evaluation/.env
cp backend/ms-notifications/.env.example     backend/ms-notifications/.env
cp backend/ms-risk/.env.example              backend/ms-risk/.env
```

`backend/ms-credit-evaluation/.env`:

```dotenv
SERVER_PORT=8080
DB_REACTIVE_URL=
DB_USERNAME=
DB_PASSWORD=
KEYCLOAK_URL=
KEYCLOAK_REALM=
KEYCLOAK_CLIENT_ID=
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
AWS_SSM_ENDPOINT=http://localhost:4566
SSM_PREFIX=/banco/ms-credit-evaluation
```

`backend/ms-notifications/.env`:

```dotenv
SERVER_PORT=8080
DB_REACTIVE_URL=
DB_USERNAME=
DB_PASSWORD=
KEYCLOAK_URL=
KEYCLOAK_REALM=
KEYCLOAK_CLIENT_ID=
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
AWS_SSM_ENDPOINT=http://localhost:4566
SSM_PREFIX=/banco/ms-notifications
```

`backend/ms-risk/.env` no necesita cambios — el servicio es un mock y no requiere ninguna variable de entorno externa.

#### Paso 6 — Arrancar los microservicios (cada uno en su propia terminal)

```bash
# Terminal 1 — ms-risk (arrancar primero, ms-credit-evaluation depende de él)
cd backend/ms-risk/infrastructure/entry-points/app
mvn quarkus:dev

# Terminal 2 — ms-credit-evaluation
cd backend/ms-credit-evaluation/infrastructure/entry-points/app
mvn quarkus:dev

# Terminal 3 — ms-notifications
cd backend/ms-notifications/infrastructure/entry-points/app
mvn quarkus:dev
```

En modo `quarkus:dev` los cambios en el código fuente se recargan automáticamente sin reiniciar el proceso.

Swagger UI disponible en:
- `ms-credit-evaluation`: http://localhost:8080/swagger-ui
- `ms-risk`: http://localhost:8081/swagger-ui

#### Paso 7 — Frontend con hot-reload

```bash
cd frontend
npm install       # solo la primera vez
npm run dev
```

El servidor Vite queda disponible en `http://localhost:5173` con hot module replacement.

> En modo dev el frontend habla directamente con `ms-credit-evaluation` en `http://localhost:8080`. En Docker usa el proxy Nginx en el puerto 3000.

---

### Crear usuarios de prueba en Keycloak

El realm `banco` se importa sin usuarios. Hay que crearlos manualmente una sola vez, ya sea vía Admin Console o vía API.

#### Vía Admin Console (interfaz gráfica)

1. Abrir `http://localhost:9000` e ingresar con `admin / admin`.
2. Seleccionar el realm **banco** en el selector superior izquierdo.
3. Ir a **Users → Add user**. Completar `username` y `email`, guardar.
4. En la pestaña **Credentials**: establecer una contraseña y desactivar **Temporary**.
5. En la pestaña **Role mappings → Assign role**: asignar uno de los roles del realm: `ANALYST`, `ADMIN` o `VIEWER`.
6. Repetir para cada usuario de prueba que se necesite.

#### Vía API (un solo comando por usuario)

```bash
# 1. Obtener token de administrador
ADMIN_TOKEN=$(curl -s \
  -d 'client_id=admin-cli&username=admin&password=admin&grant_type=password' \
  'http://localhost:9000/realms/master/protocol/openid-connect/token' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')

# 2. Crear usuario analista
curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST "http://localhost:9000/admin/realms/banco/users" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "analista1",
    "email": "analista1@banco.com",
    "enabled": true,
    "credentials": [{"type": "password", "value": "password123", "temporary": false}],
    "realmRoles": ["ANALYST"]
  }'
```

Roles disponibles: `ANALYST` (evaluar y consultar), `ADMIN` (todo + gestión), `VIEWER` (solo consultar).

---

### Primer uso del sistema

Con el stack levantado y al menos un usuario creado:

1. Abrir `http://localhost:3000` (Opción A) o `http://localhost:5173` (Opción B dev).
2. Hacer clic en **Iniciar sesión** — el frontend redirige a Keycloak (OIDC Authorization Code + PKCE).
3. Ingresar con las credenciales del usuario creado (p. ej. `analista1 / password123`).
4. Una vez autenticado, completar el formulario de evaluación de crédito.
5. El sistema consulta `ms-risk` en paralelo (score + deudas), aplica la regla de negocio y persiste el resultado.
6. `ms-credit-evaluation` publica un mensaje en SQS; `ms-notifications` lo consume en segundos y simula el envío de un email vía AWS SES (LocalStack lo intercepta sin enviarlo realmente).

#### Verificar el flujo completo por consola

```bash
# Ver los logs de los microservicios en tiempo real (Opción A)
docker compose logs -f ms-credit-evaluation ms-notifications

# Confirmar que el mensaje llegó a SQS (contador debe bajar a 0 tras unos segundos)
docker exec localstack awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/credit-evaluation-notifications \
  --attribute-names ApproximateNumberOfMessages

# Ver emails "enviados" por LocalStack SES
docker exec localstack awslocal ses list-identities
```

---

### Comandos útiles

```bash
# ── Docker Compose ────────────────────────────────────────────
docker compose ps                          # estado de todos los servicios
docker compose logs -f <servicio>          # logs en tiempo real de un servicio
docker compose restart <servicio>          # reiniciar un servicio sin bajar el resto
docker compose down                        # bajar todo (conserva volúmenes/datos)
docker compose down -v                     # bajar todo y borrar volúmenes (reset completo)

# ── Solo infraestructura (Opción B) ──────────────────────────
docker compose -f docker-compose.infra.yml up -d
docker compose -f docker-compose.infra.yml down

# ── Bases de datos ────────────────────────────────────────────
psql -h localhost -p 5432 -U postgres -d creditos_db       # ms-credit-evaluation
psql -h localhost -p 5434 -U postgres -d notifications_db  # ms-notifications
psql -h localhost -p 5435 -U postgres -d keycloak_db       # Keycloak (contraseña: postgres)

# ── LocalStack ────────────────────────────────────────────────
docker exec localstack awslocal sqs list-queues
docker exec localstack awslocal ssm get-parameters-by-path \
  --path "/banco/" --recursive --output table

# ── Healthchecks ──────────────────────────────────────────────
curl -s http://localhost:8080/q/health    # ms-credit-evaluation
curl -s http://localhost:8081/q/health    # ms-risk
curl -s http://localhost:8083/q/health    # ms-notifications
curl -s http://localhost:9000/realms/banco/.well-known/openid-configuration   # Keycloak realm
```
