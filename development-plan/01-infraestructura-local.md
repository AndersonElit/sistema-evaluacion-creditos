# Paso 01 — Infraestructura Local (Docker Compose base)

## Objetivo
Levantar todos los servicios de infraestructura necesarios para el desarrollo local:
tres instancias de PostgreSQL, Keycloak y LocalStack (SQS + SES).
Los microservicios Java y el Frontend corren fuera de Docker en esta etapa (modo dev con `mvn quarkus:dev`).

## Prerrequisitos
- Docker y Docker Compose instalados (`docker --version`, `docker compose version`)
- Puertos disponibles: 5432, 5434, 5435, 9000, 4566

## Archivos a crear

### `docker-compose.infra.yml`
```yaml
version: "3.9"

networks:
  creditos-net:
    driver: bridge

services:

  # ── PostgreSQL: ms-credit-evaluation ──────────────────────────
  postgres-credits:
    image: postgres:16
    container_name: postgres-credits
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: creditos_db
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    networks:
      - creditos-net
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d creditos_db"]
      interval: 5s
      retries: 5

  # ── PostgreSQL: ms-notifications ──────────────────────────────
  postgres-notifications:
    image: postgres:16
    container_name: postgres-notifications
    ports:
      - "5434:5432"
    environment:
      POSTGRES_DB: notifications_db
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    networks:
      - creditos-net
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d notifications_db"]
      interval: 5s
      retries: 5

  # ── PostgreSQL: Keycloak ───────────────────────────────────────
  postgres-keycloak:
    image: postgres:16
    container_name: postgres-keycloak
    ports:
      - "5435:5432"
    environment:
      POSTGRES_DB: keycloak_db
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    networks:
      - creditos-net
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d keycloak_db"]
      interval: 5s
      retries: 5

  # ── Keycloak ──────────────────────────────────────────────────
  keycloak:
    image: quay.io/keycloak/keycloak:24.0
    container_name: keycloak
    command: start-dev
    ports:
      - "9000:8080"
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres-keycloak:5432/keycloak_db
      KC_DB_USERNAME: postgres
      KC_DB_PASSWORD: postgres
    depends_on:
      postgres-keycloak:
        condition: service_healthy
    networks:
      - creditos-net
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:8080/health/ready || exit 1"]
      interval: 10s
      retries: 10
      start_period: 30s

  # ── LocalStack (SQS + SES + SSM) ─────────────────────────────
  localstack:
    image: localstack/localstack:3.0
    container_name: localstack
    ports:
      - "4566:4566"
    environment:
      SERVICES: sqs,ses,ssm
      DEFAULT_REGION: us-east-1
      LOCALSTACK_AUTH_TOKEN: ""
    volumes:
      - ./scripts/localstack-init.sh:/etc/localstack/init/ready.d/init.sh
    networks:
      - creditos-net
    healthcheck:
      test: ["CMD-SHELL", "awslocal sqs list-queues || exit 1"]
      interval: 5s
      retries: 10
      start_period: 15s
```

### `scripts/localstack-init.sh`
```bash
#!/bin/bash
set -e

# ──────────────────────────────────────────────────────────────
# SQS
# ──────────────────────────────────────────────────────────────
echo "[LocalStack] Creando colas SQS..."

awslocal sqs create-queue \
  --queue-name credit-eval-notif-dlq \
  --attributes MessageRetentionPeriod=1209600

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/credit-eval-notif-dlq \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue \
  --queue-name credit-evaluation-notifications \
  --attributes \
    VisibilityTimeout=60,\
    ReceiveMessageWaitTimeSeconds=20,\
    MessageRetentionPeriod=86400,\
    RedrivePolicy="{\"deadLetterTargetArn\":\"${DLQ_ARN}\",\"maxReceiveCount\":\"3\"}"

# ──────────────────────────────────────────────────────────────
# SES
# ──────────────────────────────────────────────────────────────
echo "[LocalStack] Verificando identidad SES..."
awslocal ses verify-email-identity --email-address noreply@banco.com

# ──────────────────────────────────────────────────────────────
# SSM Parameter Store — ms-credit-evaluation
# ──────────────────────────────────────────────────────────────
echo "[LocalStack] Creando parámetros SSM para ms-credit-evaluation..."

QUEUE_URL="http://localstack:4566/000000000000/credit-evaluation-notifications"
SQS_ENDPOINT="http://localstack:4566"
KC_BASE="http://keycloak:9000"

awslocal ssm put-parameter --name "/banco/ms-credit-evaluation/quarkus.datasource.username" \
  --value "postgres" --type String --overwrite

awslocal ssm put-parameter --name "/banco/ms-credit-evaluation/quarkus.datasource.password" \
  --value "postgres" --type SecureString --overwrite

awslocal ssm put-parameter --name "/banco/ms-credit-evaluation/quarkus.datasource.reactive.url" \
  --value "postgresql://postgres-credits:5432/creditos_db" --type String --overwrite

awslocal ssm put-parameter --name "/banco/ms-credit-evaluation/quarkus.rest-client.risk-service.url" \
  --value "http://ms-risk:8081" --type String --overwrite

awslocal ssm put-parameter --name "/banco/ms-credit-evaluation/mp.jwt.verify.publickey.location" \
  --value "${KC_BASE}/realms/banco/protocol/openid-connect/certs" --type String --overwrite

awslocal ssm put-parameter --name "/banco/ms-credit-evaluation/mp.jwt.verify.issuer" \
  --value "${KC_BASE}/realms/banco" --type String --overwrite

awslocal ssm put-parameter --name "/banco/ms-credit-evaluation/quarkus.sqs.endpoint-override" \
  --value "${SQS_ENDPOINT}" --type String --overwrite

awslocal ssm put-parameter --name "/banco/ms-credit-evaluation/sqs.queue.url" \
  --value "${QUEUE_URL}" --type String --overwrite

awslocal ssm put-parameter --name "/banco/ms-credit-evaluation/quarkus.http.cors.origins" \
  --value "http://localhost:3000" --type String --overwrite

# ──────────────────────────────────────────────────────────────
# SSM Parameter Store — ms-notifications
# ──────────────────────────────────────────────────────────────
echo "[LocalStack] Creando parámetros SSM para ms-notifications..."

awslocal ssm put-parameter --name "/banco/ms-notifications/quarkus.datasource.username" \
  --value "postgres" --type String --overwrite

awslocal ssm put-parameter --name "/banco/ms-notifications/quarkus.datasource.password" \
  --value "postgres" --type SecureString --overwrite

awslocal ssm put-parameter --name "/banco/ms-notifications/quarkus.datasource.reactive.url" \
  --value "postgresql://postgres-notifications:5434/notifications_db" --type String --overwrite

awslocal ssm put-parameter --name "/banco/ms-notifications/quarkus.sqs.endpoint-override" \
  --value "${SQS_ENDPOINT}" --type String --overwrite

awslocal ssm put-parameter --name "/banco/ms-notifications/sqs.queue.url" \
  --value "${QUEUE_URL}" --type String --overwrite

awslocal ssm put-parameter --name "/banco/ms-notifications/quarkus.ses.endpoint-override" \
  --value "${SQS_ENDPOINT}" --type String --overwrite

awslocal ssm put-parameter --name "/banco/ms-notifications/aws.ses.from.email" \
  --value "noreply@banco.com" --type String --overwrite

echo "[LocalStack] Inicialización completa."
awslocal sqs list-queues
awslocal ssm get-parameters-by-path --path "/banco/" --recursive \
  | jq '[.Parameters[] | {Name, Type}]'
```

## Comandos de ejecución

```bash
# 1. Crear carpeta de scripts
mkdir -p scripts
chmod +x scripts/localstack-init.sh

# 2. Levantar infraestructura
docker compose -f docker-compose.infra.yml up -d

# 3. Esperar a que Keycloak esté listo (~30s)
docker compose -f docker-compose.infra.yml logs -f keycloak
# Esperar línea: "Keycloak X.Y.Z on JVM started"
```

## Verificación

```bash
# PostgreSQL creditos_db
psql -h localhost -p 5432 -U postgres -d creditos_db -c "SELECT version();"

# PostgreSQL notifications_db
psql -h localhost -p 5434 -U postgres -d notifications_db -c "SELECT version();"

# Keycloak Admin Console
curl -s http://localhost:9000/health/ready | grep '"status":"UP"'

# LocalStack: listar colas SQS
aws --endpoint-url=http://localhost:4566 sqs list-queues \
  --region us-east-1 \
  --no-sign-request
# Esperado: credit-evaluation-notifications y credit-eval-notif-dlq

# Keycloak Admin Console en navegador:
# http://localhost:9000/admin  (admin / admin)
```

## Estado esperado al finalizar
- [ ] `postgres-credits` healthy en puerto 5432
- [ ] `postgres-notifications` healthy en puerto 5434
- [ ] `postgres-keycloak` healthy en puerto 5435
- [ ] `keycloak` accesible en http://localhost:9000/admin
- [ ] `localstack` con colas `credit-evaluation-notifications` y `credit-eval-notif-dlq` creadas
- [ ] Parámetros SSM de ms-credit-evaluation y ms-notifications creados en `/banco/`
