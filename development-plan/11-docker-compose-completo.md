# Paso 11 — Docker Compose Completo: Dockerfiles y Stack Unificado

## Objetivo
Crear los `Dockerfile` para cada microservicio Java y el Frontend,
y un `docker-compose.yml` unificado que levante todo el sistema
con un solo `docker compose up`.

## Prerrequisitos
- Pasos 03 al 10 completados y funcionando en modo dev
- Docker instalado

## 1. Dockerfile — ms-risk

### `ms-risk/Dockerfile`
```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/infrastructure/entry-points/app/target/quarkus-app/ ./quarkus-app/
EXPOSE 8081
CMD ["java", "-jar", "quarkus-app/quarkus-run.jar"]
```

## 2. Dockerfile — ms-credit-evaluation

### `ms-credit-evaluation/Dockerfile`
```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/infrastructure/entry-points/app/target/quarkus-app/ ./quarkus-app/
EXPOSE 8080
CMD ["java", "-jar", "quarkus-app/quarkus-run.jar"]
```

## 3. Dockerfile — ms-notifications

### `ms-notifications/Dockerfile`
```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/infrastructure/entry-points/app/target/quarkus-app/ ./quarkus-app/
EXPOSE 8083
CMD ["java", "-jar", "quarkus-app/quarkus-run.jar"]
```

## 4. Dockerfile — Frontend

### `frontend/Dockerfile`
```dockerfile
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 3000
CMD ["nginx", "-g", "daemon off;"]
```

### `frontend/nginx.conf`
```nginx
server {
    listen 3000;
    root /usr/share/nginx/html;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://ms-credit-evaluation:8080/;
    }
}
```

## 5. `docker-compose.yml` — Stack Completo

```yaml
version: "3.9"

networks:
  creditos-net:
    driver: bridge

services:

  # ──────────────────────────────────────────────
  # INFRAESTRUCTURA
  # ──────────────────────────────────────────────

  postgres-credits:
    image: postgres:16
    container_name: postgres-credits
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: creditos_db
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    networks: [creditos-net]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d creditos_db"]
      interval: 5s
      retries: 5

  postgres-notifications:
    image: postgres:16
    container_name: postgres-notifications
    ports:
      - "5434:5432"
    environment:
      POSTGRES_DB: notifications_db
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    networks: [creditos-net]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d notifications_db"]
      interval: 5s
      retries: 5

  postgres-keycloak:
    image: postgres:16
    container_name: postgres-keycloak
    ports:
      - "5435:5432"
    environment:
      POSTGRES_DB: keycloak_db
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    networks: [creditos-net]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d keycloak_db"]
      interval: 5s
      retries: 5

  keycloak:
    image: quay.io/keycloak/keycloak:24.0
    container_name: keycloak
    command: start-dev --import-realm
    ports:
      - "9000:8080"
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres-keycloak:5432/keycloak_db
      KC_DB_USERNAME: postgres
      KC_DB_PASSWORD: postgres
    volumes:
      - ./scripts/keycloak-realm-banco.json:/opt/keycloak/data/import/realm-banco.json
    depends_on:
      postgres-keycloak:
        condition: service_healthy
    networks: [creditos-net]

  localstack:
    image: localstack/localstack:3.0
    container_name: localstack
    ports:
      - "4566:4566"
    environment:
      SERVICES: sqs,ses
      DEFAULT_REGION: us-east-1
    volumes:
      - ./scripts/localstack-init.sh:/etc/localstack/init/ready.d/init.sh
    networks: [creditos-net]

  # ──────────────────────────────────────────────
  # MICROSERVICIOS
  # ──────────────────────────────────────────────

  ms-risk:
    build:
      context: ./ms-risk
      dockerfile: Dockerfile
    container_name: ms-risk
    ports:
      - "8081:8081"
    networks: [creditos-net]
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:8081/q/health || exit 1"]
      interval: 10s
      retries: 5
      start_period: 30s

  ms-credit-evaluation:
    build:
      context: ./ms-credit-evaluation
      dockerfile: Dockerfile
    container_name: ms-credit-evaluation
    ports:
      - "8080:8080"
    environment:
      DB_HOST: postgres-credits
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      # Keycloak — dentro de Docker usa el nombre de contenedor
      # El JWT issuer debe coincidir con cómo el browser accede a Keycloak
      # mp.jwt.verify.issuer se setea en application.properties con localhost:9000
      # La verificación de claves usa el nombre de red interno
      SQS_QUEUE_URL: http://localstack:4566/000000000000/credit-evaluation-notifications
      SQS_ENDPOINT_URL: http://localstack:4566
      AWS_REGION: us-east-1
      AWS_ACCESS_KEY_ID: test
      AWS_SECRET_ACCESS_KEY: test
    depends_on:
      postgres-credits:
        condition: service_healthy
      ms-risk:
        condition: service_healthy
      keycloak:
        condition: service_started
      localstack:
        condition: service_started
    networks: [creditos-net]
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:8080/q/health || exit 1"]
      interval: 10s
      retries: 5
      start_period: 40s

  ms-notifications:
    build:
      context: ./ms-notifications
      dockerfile: Dockerfile
    container_name: ms-notifications
    ports:
      - "8083:8083"
    environment:
      NOTIF_DB_HOST: postgres-notifications
      NOTIF_DB_USERNAME: postgres
      NOTIF_DB_PASSWORD: postgres
      SQS_QUEUE_URL: http://localstack:4566/000000000000/credit-evaluation-notifications
      SQS_ENDPOINT_URL: http://localstack:4566
      SES_ENDPOINT_URL: http://localstack:4566
      SES_FROM_EMAIL: noreply@banco.com
      AWS_REGION: us-east-1
      AWS_ACCESS_KEY_ID: test
      AWS_SECRET_ACCESS_KEY: test
    depends_on:
      postgres-notifications:
        condition: service_healthy
      localstack:
        condition: service_started
    networks: [creditos-net]
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:8083/q/health || exit 1"]
      interval: 10s
      retries: 5
      start_period: 40s

  # ──────────────────────────────────────────────
  # FRONTEND
  # ──────────────────────────────────────────────

  frontend:
    build:
      context: ./frontend
      dockerfile: Dockerfile
    container_name: frontend
    ports:
      - "3000:3000"
    depends_on:
      ms-credit-evaluation:
        condition: service_healthy
    networks: [creditos-net]
```

## 6. Construir imágenes y levantar todo

```bash
# Build de todas las imágenes (puede tomar 5-10 min la primera vez)
docker compose build

# Levantar todo
docker compose up -d

# Ver logs en tiempo real
docker compose logs -f

# Ver solo los microservicios Java
docker compose logs -f ms-risk ms-credit-evaluation ms-notifications
```

## 7. Verificación del stack completo

```bash
# Estado de todos los contenedores
docker compose ps

# Health de cada servicio
curl -s http://localhost:8080/q/health | jq .status  # ms-credit-evaluation: UP
curl -s http://localhost:8081/q/health | jq .status  # ms-risk: UP
curl -s http://localhost:8083/q/health | jq .status  # ms-notifications: UP
curl -s http://localhost:9000/health/ready            # Keycloak: UP

# Frontend
curl -s -o /dev/null -w "%{http_code}" http://localhost:3000
# 200
```

## 8. Teardown

```bash
# Detener y eliminar contenedores (conserva volúmenes/datos)
docker compose down

# Eliminar también volúmenes (reset completo)
docker compose down -v
```

## Estado esperado al finalizar
- [ ] Todos los contenedores sanos (`docker compose ps` muestra `healthy`)
- [ ] `http://localhost:3000` muestra la app React
- [ ] Login en Keycloak funciona desde el browser
- [ ] Evaluación creada desde el Frontend se persiste en `creditos_db`
- [ ] Mensaje SQS publicado y consumido por `ms-notifications`
- [ ] Email registrado en LocalStack SES
