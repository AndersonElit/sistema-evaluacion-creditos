# Paso 09b — SSM Parameter Store: Verificación e Integración con Quarkus

## Objetivo
Verificar que todos los parámetros SSM fueron creados correctamente por el `init.sh`,
e integrar la extensión `quarkus-config-aws-ssm` en `ms-credit-evaluation` y `ms-notifications`
para que carguen su configuración de runtime desde SSM en lugar de `application.properties`.

> Este paso se ejecuta después del paso 09 (LocalStack SQS/SES). Los parámetros SSM
> ya deben existir si el init script corrió correctamente.

## Prerrequisitos
- Paso 01 completado (LocalStack corriendo con `SERVICES: sqs,ses,ssm`)
- `awslocal` disponible (ver alias en paso 09)

---

## 1. Verificar parámetros SSM en LocalStack

```bash
# Listar todos los parámetros del proyecto
awslocal ssm get-parameters-by-path \
  --path "/banco/" \
  --recursive \
  | jq '[.Parameters[] | {Name, Type}]'

# Debe retornar los parámetros de ms-credit-evaluation y ms-notifications
# con sus tipos String o SecureString
```

```bash
# Verificar un parámetro SecureString (credencial de BD)
awslocal ssm get-parameter \
  --name "/banco/ms-credit-evaluation/quarkus.datasource.password" \
  --with-decryption \
  | jq '.Parameter.Value'
# Esperado: "postgres"
```

---

## 2. Si los parámetros no existen — crearlos manualmente

Ejecutar el init script manualmente contra LocalStack:
```bash
# Desde la raíz del proyecto
bash scripts/localstack-init.sh
```

O crear parámetros individuales:
```bash
awslocal ssm put-parameter \
  --name "/banco/ms-credit-evaluation/quarkus.datasource.password" \
  --value "postgres" \
  --type SecureString \
  --overwrite
```

---

## 3. Agregar la extensión a ms-credit-evaluation

### 3.1 pom.xml del módulo app

Archivo: `backend/ms-credit-evaluation/infrastructure/entry-points/app/pom.xml`

Agregar dentro de `<dependencies>`:
```xml
<!-- SSM Config Source -->
<dependency>
    <groupId>io.quarkiverse.config</groupId>
    <artifactId>quarkus-config-aws-ssm</artifactId>
    <version>2.1.0</version>
</dependency>
```

### 3.2 application.properties

Reemplazar el contenido actual por:
```properties
# ── SSM Config Source ──────────────────────────────────────────
# Todos los valores de entorno (credenciales, URLs) se leen de SSM.
# Solo permanece aquí la config estructural que no varía entre entornos.
quarkus.ssm.aws.region=us-east-1
quarkus.ssm.endpoint-override=${AWS_SSM_ENDPOINT:http://localhost:4566}
quarkus.config.source.aws.ssm.prefix=/banco/ms-credit-evaluation
quarkus.config.source.aws.ssm.ordinal=280

# ── Config estructural ─────────────────────────────────────────
quarkus.http.port=8080
quarkus.datasource.db-kind=postgresql
quarkus.smallrye-jwt.role-paths=groups
quarkus.http.cors=true
quarkus.swagger-ui.always-include=true
mp.openapi.extensions.smallrye.info.version=1.0.0
```

### 3.3 Variables de entorno para desarrollo local (mvn quarkus:dev)

Crear `.env` en la raíz del proyecto (ya debe estar en `.gitignore`):
```dotenv
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
AWS_REGION=us-east-1
AWS_SSM_ENDPOINT=http://localhost:4566
```

O exportar en la shell:
```bash
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_REGION=us-east-1
export AWS_SSM_ENDPOINT=http://localhost:4566

cd backend/ms-credit-evaluation
mvn quarkus:dev
```

---

## 4. Agregar la extensión a ms-notifications

Mismo proceso que el paso 3, aplicado a `ms-notifications`.

### 4.1 pom.xml del módulo app

Archivo: `backend/ms-notifications/infrastructure/entry-points/app/pom.xml`

```xml
<dependency>
    <groupId>io.quarkiverse.config</groupId>
    <artifactId>quarkus-config-aws-ssm</artifactId>
    <version>2.1.0</version>
</dependency>
```

### 4.2 application.properties

```properties
# ── SSM Config Source ──────────────────────────────────────────
quarkus.ssm.aws.region=us-east-1
quarkus.ssm.endpoint-override=${AWS_SSM_ENDPOINT:http://localhost:4566}
quarkus.config.source.aws.ssm.prefix=/banco/ms-notifications
quarkus.config.source.aws.ssm.ordinal=280

# ── Config estructural ─────────────────────────────────────────
quarkus.http.port=8083
quarkus.datasource.db-kind=postgresql
quarkus.swagger-ui.always-include=true
```

---

## 5. Actualizar docker-compose.yml (stack completo)

En `docker-compose.yml`, cada microservicio recibe las credenciales AWS para conectarse
a LocalStack. **No se definen variables de configuración de negocio** — esas vienen de SSM.

```yaml
ms-credit-evaluation:
  environment:
    AWS_ACCESS_KEY_ID: test
    AWS_SECRET_ACCESS_KEY: test
    AWS_REGION: us-east-1
    AWS_SSM_ENDPOINT: http://localstack:4566
    # Sin DB_USERNAME, DB_PASSWORD, RISK_SERVICE_URL, etc.
  depends_on:
    localstack:
      condition: service_healthy

ms-notifications:
  environment:
    AWS_ACCESS_KEY_ID: test
    AWS_SECRET_ACCESS_KEY: test
    AWS_REGION: us-east-1
    AWS_SSM_ENDPOINT: http://localstack:4566
  depends_on:
    localstack:
      condition: service_healthy
```

> El endpoint `http://localstack:4566` es el nombre del servicio en la red Docker interna.
> Los microservicios lo usan para conectarse a LocalStack SSM al arrancar.

---

## 6. Verificar carga de configuración

### 6.1 Con mvn quarkus:dev

Al arrancar, el log debe mostrar que SSM fue consultado:
```
INFO  [io.qua.con.aws.ssm] SSM config source: loaded 9 parameters from /banco/ms-credit-evaluation
```

Verificar que la BD conecta correctamente (Flyway debe ejecutar las migraciones sin error).

### 6.2 Con curl

```bash
# El endpoint de health debe estar UP (indicando que la BD conectó via config de SSM)
curl -s http://localhost:8080/q/health | jq '.status'
# Esperado: "UP"

# El swagger debe estar accesible
curl -s http://localhost:8080/q/openapi | head -5
```

### 6.3 Verificar que no hay valores hardcodeados en application.properties

```bash
# Ninguno de estos grep debe retornar resultados
grep -r "DB_USERNAME\|DB_PASSWORD\|postgres:5432\|risk-service.url\|sqs.endpoint" \
  backend/ms-credit-evaluation/infrastructure/entry-points/app/src/main/resources/

grep -r "DB_USERNAME\|DB_PASSWORD\|notifications:5434\|ses.endpoint" \
  backend/ms-notifications/infrastructure/entry-points/app/src/main/resources/
```

---

## 7. Configuración de tests

Los tests de integración deben deshabilitar SSM para no depender de LocalStack:

```properties
# backend/ms-credit-evaluation/infrastructure/entry-points/app/src/main/resources/application-test.properties
%test.quarkus.config.source.aws.ssm.enabled=false

# Valores directos para tests
%test.quarkus.datasource.username=postgres
%test.quarkus.datasource.password=postgres
%test.quarkus.datasource.reactive.url=postgresql://localhost:5432/creditos_db
```

O usar `@QuarkusTestProfile` con overrides puntuales en los ITs que sí requieran el stack AWS.

---

## Estado esperado al finalizar

- [x] `awslocal ssm get-parameters-by-path --path /banco/ --recursive` retorna todos los parámetros
- [x] ms-credit-evaluation arranca sin credenciales en `application.properties`
- [x] ms-notifications arranca sin credenciales en `application.properties`
- [x] `/q/health` retorna UP en ambos microservicios
- [x] `grep` de credenciales en application.properties no retorna resultados
- [x] Tests unitarios pasan con SSM deshabilitado en perfil test
