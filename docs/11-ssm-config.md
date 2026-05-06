# Gestión de Configuración con AWS SSM Parameter Store

> Lineamiento arquitectónico: ningún valor de entorno (credenciales, URLs, endpoints) vive en `application.properties` comiteado. Todo valor que difiere entre entornos reside en AWS SSM Parameter Store y se carga en el arranque del microservicio vía `quarkus-config-aws-ssm`.

---

## 1. Motivación

Los `application.properties` originales usaban el patrón `${ENV_VAR:default}`, delegando la inyección de valores al Docker Compose o al sistema operativo. Este enfoque tiene varios problemas:

| Problema | Impacto |
|----------|---------|
| Credenciales en variables de entorno del contenedor | Visibles en `docker inspect`, logs de orquestador, dumps de memoria |
| Sin distinción entre secreto y config ordinaria | Una URL de BD y una contraseña se tratan igual |
| Rotación requiere reinicio del contenedor | No hay separación entre ciclo de vida del secreto y el servicio |
| Sin auditoría de quién leyó qué | Imposible saber si un proceso accedió a una credencial |
| Difícil replicar producción en local | El `.env` local puede divergir de los valores reales sin detección |

Con SSM Parameter Store + LocalStack, el mismo mecanismo funciona en desarrollo, staging y producción. Solo cambia el endpoint de SSM.

---

## 2. Jerarquía de Parámetros

Todos los parámetros siguen la convención:

```
/banco/<microservicio>/<quarkus.property.name>
```

El prefijo es `/banco/ms-<servicio>/`. La extensión `quarkus-config-aws-ssm` lo elimina y expone el resto como propiedad MicroProfile Config.

### 2.1 Parámetros de ms-credit-evaluation

| Nombre SSM | Tipo | Valor (local/dev) |
|-----------|------|-------------------|
| `/banco/ms-credit-evaluation/quarkus.datasource.username` | String | `postgres` |
| `/banco/ms-credit-evaluation/quarkus.datasource.password` | SecureString | `postgres` |
| `/banco/ms-credit-evaluation/quarkus.datasource.reactive.url` | String | `postgresql://postgres-credits:5432/creditos_db` |
| `/banco/ms-credit-evaluation/quarkus.rest-client.risk-service.url` | String | `http://ms-risk:8081` |
| `/banco/ms-credit-evaluation/mp.jwt.verify.publickey.location` | String | `http://keycloak:9000/realms/banco/protocol/openid-connect/certs` |
| `/banco/ms-credit-evaluation/mp.jwt.verify.issuer` | String | `http://keycloak:9000/realms/banco` |
| `/banco/ms-credit-evaluation/quarkus.sqs.endpoint-override` | String | `http://localstack:4566` |
| `/banco/ms-credit-evaluation/sqs.queue.url` | String | `http://localstack:4566/000000000000/credit-evaluation-notifications` |
| `/banco/ms-credit-evaluation/quarkus.http.cors.origins` | String | `http://localhost:3000` |

### 2.2 Parámetros de ms-notifications

| Nombre SSM | Tipo | Valor (local/dev) |
|-----------|------|-------------------|
| `/banco/ms-notifications/quarkus.datasource.username` | String | `postgres` |
| `/banco/ms-notifications/quarkus.datasource.password` | SecureString | `postgres` |
| `/banco/ms-notifications/quarkus.datasource.reactive.url` | String | `postgresql://postgres-notifications:5434/notifications_db` |
| `/banco/ms-notifications/quarkus.sqs.endpoint-override` | String | `http://localstack:4566` |
| `/banco/ms-notifications/sqs.queue.url` | String | `http://localstack:4566/000000000000/credit-evaluation-notifications` |
| `/banco/ms-notifications/quarkus.ses.endpoint-override` | String | `http://localstack:4566` |
| `/banco/ms-notifications/aws.ses.from.email` | String | `noreply@banco.com` |

### 2.3 Parámetros de ms-risk

ms-risk no tiene base de datos ni credenciales externas. Sus únicos valores de entorno (puerto y configuración de Swagger) se dejan en `application.properties` al ser puramente estructurales y no variar entre entornos.

---

## 3. Integración con Quarkus

### 3.1 Dependencia Maven

Agregar en el `pom.xml` del módulo `infrastructure/entry-points/app` de cada microservicio:

```xml
<dependency>
    <groupId>io.quarkiverse.config</groupId>
    <artifactId>quarkus-config-aws-ssm</artifactId>
    <version>2.1.0</version>
</dependency>
```

La extensión trae transitivamente el cliente `software.amazon.awssdk:ssm`.

### 3.2 Configuración en application.properties

Solo se requiere configuración del propio SSM client y el prefijo de lectura. El resto del `application.properties` queda únicamente con config estructural:

**ms-credit-evaluation** (`application.properties`):
```properties
# ── SSM Config Source ──────────────────────────────────────────
quarkus.ssm.aws.region=us-east-1
quarkus.ssm.endpoint-override=${AWS_SSM_ENDPOINT:http://localhost:4566}
quarkus.config.source.aws.ssm.prefix=/banco/ms-credit-evaluation
# Ordinal 280: SSM supera a application.properties (250) pero cede a env vars (300)
quarkus.config.source.aws.ssm.ordinal=280

# ── Config estructural (no varía entre entornos) ───────────────
quarkus.http.port=8080
quarkus.datasource.db-kind=postgresql
quarkus.smallrye-jwt.role-paths=groups
quarkus.http.cors=true
quarkus.swagger-ui.always-include=true
mp.openapi.extensions.smallrye.info.version=1.0.0
```

**ms-notifications** (`application.properties`):
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

### 3.3 Prioridad de fuentes de configuración

```
Prioridad (mayor → menor)
─────────────────────────────────────────────────────
400  Propiedades del sistema (-D flags de JVM)
300  Variables de entorno del SO / contenedor
280  AWS SSM Parameter Store  ← nueva fuente
250  application.properties
100  Valores por defecto de extensiones
```

Esto permite sobrescribir un parámetro SSM con una variable de entorno en caso de emergencia, sin redesplegar.

---

## 4. Configuración por Entorno

### 4.1 Desarrollo local (mvn quarkus:dev)

El microservicio apunta a LocalStack en `localhost:4566`. Los parámetros SSM son creados automáticamente por `scripts/localstack-init.sh` al arrancar el contenedor de LocalStack.

```bash
# Credenciales ficticias (LocalStack no valida)
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_REGION=us-east-1
export AWS_SSM_ENDPOINT=http://localhost:4566

mvn quarkus:dev
```

Las credenciales se pueden definir también en `.env` (excluido de git):
```dotenv
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
AWS_REGION=us-east-1
AWS_SSM_ENDPOINT=http://localhost:4566
```

### 4.2 Docker Compose (stack completo)

Cada servicio de microservicio en `docker-compose.yml` recibe solo las variables de conexión a SSM:

```yaml
ms-credit-evaluation:
  environment:
    AWS_ACCESS_KEY_ID: test
    AWS_SECRET_ACCESS_KEY: test
    AWS_REGION: us-east-1
    AWS_SSM_ENDPOINT: http://localstack:4566
    # Ninguna credencial de BD, URL de servicio, ni endpoint aquí
```

El endpoint interno es `http://localstack:4566` (nombre del servicio en la red Docker).

### 4.3 Producción (AWS ECS / EKS)

- `AWS_SSM_ENDPOINT` no se define → el SDK usa el endpoint público de AWS SSM.
- Las credenciales vienen del IAM Role del task/pod — no se pasan variables `AWS_ACCESS_KEY_ID` ni `AWS_SECRET_ACCESS_KEY`.
- Los parámetros `SecureString` son descifrados automáticamente por SSM con la KMS key del entorno.
- La política IAM del rol permite únicamente `ssm:GetParametersByPath` sobre `/banco/ms-<servicio>/*`.

Política IAM mínima por servicio:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["ssm:GetParametersByPath", "ssm:GetParameter"],
      "Resource": "arn:aws:ssm:<region>:<account>:parameter/banco/ms-credit-evaluation/*"
    },
    {
      "Effect": "Allow",
      "Action": ["kms:Decrypt"],
      "Resource": "arn:aws:kms:<region>:<account>:key/<key-id>"
    }
  ]
}
```

---

## 5. Convenciones de Nombrado

| Regla | Ejemplo |
|-------|---------|
| Prefijo obligatorio con nombre de microservicio | `/banco/ms-credit-evaluation/` |
| El nombre después del prefijo es el nombre exacto de la propiedad Quarkus/MicroProfile | `quarkus.datasource.password` |
| Contraseñas, tokens y API keys → `SecureString` | `quarkus.datasource.password` |
| URLs, nombres de cola, emails → `String` | `sqs.queue.url` |
| Sin espacios ni mayúsculas en los nombres | ✅ `/banco/ms-credit-evaluation/sqs.queue.url` |
| Sin parámetros compartidos entre microservicios | Cada MS tiene su propia copia — el acoplamiento de config es igual de peligroso que el acoplamiento de BD |

---

## 6. Operaciones de Mantenimiento

### Leer un parámetro

```bash
# Local (LocalStack)
awslocal ssm get-parameter \
  --name "/banco/ms-credit-evaluation/quarkus.datasource.password" \
  --with-decryption | jq '.Parameter.Value'

# Producción (AWS CLI configurado con el perfil correcto)
aws ssm get-parameter \
  --name "/banco/ms-credit-evaluation/quarkus.datasource.password" \
  --with-decryption | jq '.Parameter.Value'
```

### Actualizar un parámetro (rotación de credencial)

```bash
awslocal ssm put-parameter \
  --name "/banco/ms-credit-evaluation/quarkus.datasource.password" \
  --value "nueva-contraseña-segura" \
  --type SecureString \
  --overwrite
```

Tras actualizar, reiniciar el microservicio para que cargue el nuevo valor en el arranque.

### Listar todos los parámetros de un servicio

```bash
awslocal ssm get-parameters-by-path \
  --path "/banco/ms-credit-evaluation/" \
  --recursive \
  --with-decryption | jq '.Parameters[] | {Name, Type, Value}'
```

---

## 7. Testing

Los tests de integración Quarkus usan `@QuarkusTest` con `devservices.enabled=false` en el perfil de test. Para SSM en tests:

```properties
# application-test.properties o en @QuarkusTestProfile
quarkus.ssm.endpoint-override=http://localhost:4566
%test.quarkus.config.source.aws.ssm.enabled=false
```

Se recomienda deshabilitar la carga de SSM en tests unitarios y usar propiedades directas en `application-test.properties`. Solo los tests de integración (IT) que arrancan el stack completo deben cargar desde SSM/LocalStack.
