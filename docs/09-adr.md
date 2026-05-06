# ADR — Architecture Decision Records

> Registro de decisiones arquitectónicas significativas tomadas durante la fase de diseño.

---

## ADR-001: Java Quarkus como Framework Backend

**Estado:** Aceptado  
**Fecha:** 2026-05-05  
**Contexto:** Se requiere construir dos microservicios en Java con buen rendimiento, bajo consumo de memoria y soporte nativo para REST, ORM y clientes HTTP.

### Opciones evaluadas

| Criterio | Spring Boot 3 | Quarkus 3.x | Micronaut 4 |
|----------|:---:|:---:|:---:|
| Tiempo de arranque (JVM) | ~3s | **< 1s** | ~1s |
| Memoria RSS | ~250MB | **~80MB** | ~100MB |
| Dev Mode (hot reload) | DevTools | **Dev Mode** | Hot restart |
| MicroProfile JWT | ❌ | **✅ nativo** | Parcial |
| Soporte Hibernate Panache | ✅ | **✅ nativo** | Adaptado |
| SmallRye Fault Tolerance | ❌ | **✅ nativo** | ❌ |
| Curva de aprendizaje | Baja | Media | Media |

### Decisión
**Quarkus 3.x** porque ofrece MicroProfile JWT, SmallRye Fault Tolerance y Hibernate Panache de forma nativa, reduciendo la configuración boilerplate. El Dev Mode acelera el ciclo de desarrollo.

---

## ADR-002: REST vs gRPC para comunicación ms-credit-evaluation ↔ ms-risk

**Estado:** Aceptado  
**Fecha:** 2026-05-05  
**Contexto:** ms-credit-evaluation (Orquestador) necesita consultar a ms-risk (Riesgos) para obtener score y deudas. Debemos elegir el protocolo de comunicación.

### Análisis

#### REST (HTTP/1.1 + JSON)
**Ventajas:**
- Legible y depurable (curl, Postman, logs)
- Soporte nativo en todos los lenguajes y herramientas
- MicroProfile REST Client en Quarkus: anotaciones declarativas sin código de red
- Swagger UI para documentación automática
- Compatible con el Frontend si en el futuro ms-risk fuera expuesto directamente

**Desventajas:**
- Overhead de serialización JSON vs Protobuf
- No es el protocolo más eficiente para latencia ultra-baja
- Sin contrato de tipado fuerte entre servicios (mitigado con OpenAPI)

#### gRPC (HTTP/2 + Protobuf)
**Ventajas:**
- Protocolo binario: ~5x más eficiente en tamaño de payload
- Contrato fuerte (`.proto` files) como fuente de verdad
- Soporte de streaming bidireccional (no necesario aquí)
- Excelente para miles de req/s entre microservicios internos

**Desventajas:**
- Mayor complejidad: requiere Protobuf, generación de código, configuración gRPC
- Debugging más difícil (no es texto plano)
- El payload en este caso es pequeño (score: integer, deudas: lista pequeña) → el overhead de Protobuf es marginal
- Overkill para una comunicación simple request/response con latencia simulada de 2s (el cuello de botella no es el protocolo sino la latencia artificial)

### Decisión
**REST** para ms-credit-evaluation ↔ ms-risk por las siguientes razones:

1. **El cuello de botella es la latencia simulada** (2s/1.5s), no la serialización. gRPC no reduciría el tiempo total perceptiblemente.
2. **Simplicidad del contrato**: el servicio de riesgos expone solo 2 endpoints simples con payloads pequeños.
3. **Paralelismo suficiente con REST**: `MicroProfile REST Client` + `CompletableFuture.allOf()` permite llamadas paralelas que reducen la latencia de 3.5s a 2s.
4. **Consistencia**: toda la comunicación del sistema usa REST/JSON, facilitando debugging y onboarding.

> **Si el volumen de transacciones escalara a >10,000 req/s** o si ms-risk expusiera streaming de datos, la decisión debería revisarse a favor de gRPC.

---

## ADR-003: PostgreSQL como base de datos

**Estado:** Aceptado  
**Fecha:** 2026-05-05  
**Contexto:** El sistema necesita persistir evaluaciones, usuarios, roles y notificaciones con integridad referencial y transaccionalidad.

### Decisión
**PostgreSQL 16** porque:
- ACID completo: crítico para la integridad de evaluaciones financieras
- Soporte excelente en Quarkus (Hibernate Panache + JDBC)
- Tipos nativos útiles: UUID, NUMERIC, TIMESTAMPTZ, ENUM
- `TIMESTAMPTZ` para fechas con zona horaria (evita bugs de DST)
- Flyway para migraciones controladas y reproducibles

**Descartado MongoDB:** El modelo de datos es relacional (usuarios-roles, evaluaciones-notificaciones). Un SGBDR es la opción natural y más segura para datos financieros.

---

## ADR-004: JWT Stateless vs Keycloak para autenticación

**Estado:** Supersedido por ADR-010  
**Fecha original:** 2026-05-05  
**Supersedido en:** 2026-05-05

La decisión original optó por SmallRye JWT stateless (ms-auth propio) por simplicidad. Esta decisión fue revisada y reemplazada por **ADR-010** al adoptar Keycloak como IAM. Ver ADR-010 para la justificación completa.

---

## ADR-005: AWS SQS para notificaciones asíncronas

**Estado:** Aceptado  
**Fecha:** 2026-05-05  
**Contexto:** Tras una evaluación, se necesita notificar al solicitante por email sin impactar el tiempo de respuesta del endpoint.

### Opciones evaluadas

| Criterio | Llamada directa SES | SQS + Worker | RabbitMQ |
|----------|:---:|:---:|:---:|
| Tiempo respuesta API | ❌ +500ms por SES | **✅ sin impacto** | ✅ sin impacto |
| Resiliencia si SES falla | ❌ falla el endpoint | **✅ reintento automático** | ✅ reintento |
| Infraestructura adicional | ❌ ninguna | **Solo SQS (managed)** | ❌ broker propio |
| DLQ para mensajes fallidos | ❌ | **✅ nativo** | Configurable |
| Costo operativo | Bajo | **Muy bajo (managed)** | Medio |
| LocalStack para testing | - | **✅ emulación local** | ✅ |

### Decisión
**AWS SQS** porque:
1. **Desacopla** el flujo de evaluación del flujo de notificación
2. **Managed service**: sin mantenimiento de broker (vs RabbitMQ)
3. **DLQ nativa**: mensajes fallidos preservados para análisis
4. **At-least-once delivery**: garantía suficiente con idempotencia en el worker
5. **LocalStack**: permite desarrollo y testing offline sin AWS

---

## ADR-006: Validación de cédula ecuatoriana con Módulo 10

**Estado:** Aceptado  
**Fecha:** 2026-05-05  
**Contexto:** El sistema debe validar que la cédula ingresada sea matemáticamente válida antes de consultar el servicio de riesgos.

### Algoritmo Módulo 10 (Luhn ecuatoriano)

```java
public static boolean validarCedula(String cedula) {
    if (cedula == null || !cedula.matches("\\d{10}")) return false;

    // Los primeros 2 dígitos son el código de provincia (01-24)
    int provincia = Integer.parseInt(cedula.substring(0, 2));
    if (provincia < 1 || provincia > 24) return false;

    // Algoritmo de verificación
    int[] coeficientes = {2, 1, 2, 1, 2, 1, 2, 1, 2};
    int suma = 0;

    for (int i = 0; i < 9; i++) {
        int valor = Character.getNumericValue(cedula.charAt(i)) * coeficientes[i];
        suma += valor >= 10 ? valor - 9 : valor;
    }

    int digitoVerificador = Integer.parseInt(String.valueOf(cedula.charAt(9)));
    int resultado = suma % 10 == 0 ? 0 : 10 - (suma % 10);

    return resultado == digitoVerificador;
}
```

### Decisión
Implementar el algoritmo directamente como Value Object `Cedula` en el dominio, aplicado en el `@Pattern` de Bean Validation y reforzado en la entidad. Esto protege contra:
1. Cédulas ficticias que pasarían un simple regex de 10 dígitos
2. Errores de digitación del analista
3. Intentos de inyección con cadenas inusuales

---

## ADR-007: Llamadas paralelas a ms-risk

**Estado:** Aceptado  
**Fecha:** 2026-05-05  
**Contexto:** ms-credit-evaluation necesita consultar el score (2s) y las deudas (1.5s) de forma eficiente.

### Implementación con Quarkus Mutiny

```java
// Llamadas paralelas con Mutiny (reactive)
Uni<ScoreResponse> scoreUni = riesgoClient.obtenerScore(cedula);
Uni<DeudasResponse> deudasUni = riesgoClient.obtenerDeudas(cedula);

return Uni.combine().all()
    .unis(scoreUni, deudasUni)
    .with((score, deudas) -> evaluarReglaDeNegocio(score, deudas, request))
    .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
```

### Resultado
- Sin paralelismo: **3.5s** (2s + 1.5s secuencial)
- Con paralelismo: **~2s** (max(2s, 1.5s))
- Ahorro: **~43% de latencia** por request

### Circuit Breaker (SmallRye Fault Tolerance)

```java
@GET
@Path("/score/{cedula}")
@Timeout(value = 5, unit = ChronoUnit.SECONDS)
@CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 10)
@Fallback(fallbackMethod = "scoreFallback")
public ScoreResponse obtenerScore(@PathParam("cedula") String cedula) {
    return riesgoClient.obtenerScore(cedula);
}
```

Si ms-risk falla repetidamente, el Circuit Breaker abre y se retorna 503 al cliente en lugar de agotar threads esperando timeouts.

---

---

## ADR-008: Desacoplamiento de Identidad en Microservicio Independiente (ms-auth)

> **Nota:** ms-auth fue posteriormente reemplazado por Keycloak (ADR-010). El principio de separación de identidad del dominio de negocio se mantiene — solo cambia la implementación.

**Estado:** Aceptado  
**Fecha:** 2026-05-05  
**Contexto:** Originalmente, la autenticación y gestión de usuarios estaba embebida en el ms-credit-evaluation. Se evaluó si extraer esta responsabilidad a un servicio propio aportaba beneficios reales dado el tamaño del sistema.

### Opciones evaluadas

| Criterio | Auth embebida en ms-credit-evaluation | Auth en ms-auth independiente |
|----------|:---:|:---:|
| Responsabilidad única (SRP) | ❌ ms-credit-evaluation mezcla dominios | ✅ cada servicio tiene un propósito |
| Escalabilidad independiente | ❌ escala todo junto | ✅ ms-auth puede escalar por separado |
| Reutilización futura | ❌ acoplado a ms-credit-evaluation | ✅ otros servicios pueden consumir JWT de ms-auth |
| Aislamiento de BD | ❌ usuarios y evaluaciones en la misma BD | ✅ `auth_db` separada de `creditos_db` |
| Acoplamiento en runtime | — | ✅ cero: ms-credit-evaluation solo necesita la clave pública RSA |
| Complejidad operacional | Baja (1 servicio menos) | Media (un contenedor más) |
| Riesgo de despliegue | Bajo | Bajo (ms-auth no es una dependencia runtime de ms-credit-evaluation) |

### Decisión
**ms-auth independiente** por las siguientes razones:

1. **Separación de responsabilidades:** El orquestador de créditos no debe conocer ni gestionar credenciales de usuarios. Son dominios distintos (Core vs Generic).
2. **Acoplamiento cero en runtime:** Gracias a JWT firmado con RS256, ms-credit-evaluation verifica tokens con la clave pública sin necesitar llamadas HTTP a ms-auth. Si ms-auth cae, ms-credit-evaluation sigue funcionando para tokens ya emitidos.
3. **Base de datos aislada:** `auth_db` y `creditos_db` evolucionan de forma independiente. No hay FKs cruzadas — `evaluado_por_id` en `credit_evaluations` es una referencia débil por UUID.
4. **Ruta de migración clara:** Si en el futuro se adopta Keycloak o un IdP externo, solo se reemplaza ms-auth sin tocar ms-credit-evaluation ni ms-risk. Solo cambia `mp.jwt.verify.publickey.location`.

### Consecuencias
- El frontend hace dos tipos de llamadas: a ms-auth para autenticarse y a ms-credit-evaluation para operar.
- La clave pública RSA debe estar disponible en el build de ms-credit-evaluation (como archivo PEM) o descargarse de `GET /v1/auth/public-key` de ms-auth durante el arranque.
- Se agrega un contenedor al Docker Compose de desarrollo.

---

## ADR-010: Adopción de Keycloak como IAM

**Estado:** Aceptado  
**Fecha:** 2026-05-05  
**Contexto:** El sistema tenía un microservicio propio (`ms-auth`) para gestionar usuarios, roles y emitir JWT. Se evaluó si adoptar Keycloak aportaba beneficios reales frente a mantener el IAM propio.

### Opciones evaluadas

| Criterio | ms-auth propio (SmallRye JWT) | Keycloak 24.x |
|----------|:---:|:---:|
| Complejidad de deploy | Baja (Quarkus + PostgreSQL) | Media (contenedor Keycloak ~512MB+) |
| Gestión de usuarios | Manual (endpoints REST propios) | **✅ Admin Console + Admin REST API** |
| SSO / Federación LDAP | ❌ | **✅ out-of-the-box** |
| Revocación inmediata de tokens | ❌ (stateless) | **✅ logout activo + session management** |
| Brute force protection | Manual (código propio) | **✅ nativo, configurable** |
| Política de contraseñas | Manual (regex en código) | **✅ configurable sin código** |
| PKCE / OAuth2 compliant | Parcial | **✅ estándar** |
| Migración a otro IdP futuro | Requiere reemplazar ms-auth | **✅ solo cambia URL JWKS** |
| Mantenimiento de seguridad | Responsabilidad del equipo | **✅ Keycloak CVEs parchados upstream** |
| Código a mantener | Alto (auth logic, BCrypt, JWT build) | **Mínimo (solo configuración)** |

### Decisión
**Keycloak 24.x** porque:

1. **Elimina código de seguridad propio**: el equipo deja de mantener lógica de hashing, emisión de JWT, gestión de sesiones y protección contra ataques — todo lo gestiona Keycloak.
2. **Estándar OIDC/OAuth2**: cualquier cliente que entienda OIDC puede integrarse sin cambios en ms-credit-evaluation.
3. **Revocación real**: Keycloak permite logout activo e invalidar sesiones sin esperar la expiración del token.
4. **Escalabilidad futura**: SSO, federación LDAP/AD, social login son posibles sin modificar el sistema de créditos.
5. **Acoplamiento mínimo**: ms-credit-evaluation solo necesita la URL del JWKS. Reemplazar Keycloak por Auth0, AWS Cognito o cualquier OIDC Provider implica cambiar una línea en `application.properties`.

### Consecuencias
- ms-auth se elimina del sistema (código, contenedor, `auth_db`)
- Se agrega Keycloak como contenedor en Docker Compose (puerto host `9000`, imagen oficial `quay.io/keycloak/keycloak:24`)
- Frontend migra de `POST /v1/auth/login` propio a OIDC Authorization Code + PKCE con `keycloak-js`
- ms-credit-evaluation cambia `mp.jwt.verify.publickey.location` al JWKS de Keycloak
- La gestión de usuarios pasa completamente a Keycloak Admin Console / Admin REST API

---

## ADR-009: Desacoplamiento de Notificaciones en Microservicio Independiente (ms-notifications)

**Estado:** Aceptado  
**Fecha:** 2026-05-05  
**Contexto:** Originalmente, el Notification Worker estaba embebido dentro de `ms-credit-evaluation`. Se evaluó si extraer esta responsabilidad a un servicio propio aportaba beneficios reales dado el alcance del sistema.

### Opciones evaluadas

| Criterio | Worker embebido en ms-credit-evaluation | ms-notifications independiente |
|----------|:---:|:---:|
| Responsabilidad única (SRP) | ❌ ms-credit-evaluation mezcla evaluación y notificaciones | ✅ cada servicio tiene un propósito claro |
| Escalabilidad independiente | ❌ escala todo junto | ✅ ms-notifications puede escalar según volumen de emails |
| Fallos aislados | ❌ un fallo en el worker puede afectar al orquestador | ✅ fallo en ms-notifications no impacta las evaluaciones |
| Despliegue independiente | ❌ requiere redesplegar ms-credit-evaluation | ✅ se actualiza sin tocar el orquestador |
| Aislamiento de BD | ❌ notificaciones y evaluaciones en la misma BD | ✅ `notifications_db` separada de `creditos_db` |
| Acoplamiento en runtime | — | ✅ cero: ms-notifications solo consume SQS, no llama a ms-credit-evaluation |
| Complejidad operacional | Baja (1 servicio menos) | Media (un contenedor más, una BD más) |

### Decisión
**ms-notifications independiente** por las siguientes razones:

1. **Separación de responsabilidades:** El orquestador de créditos debe tener una única responsabilidad: evaluar créditos y persistir el resultado. La lógica de notificación (consumo SQS, plantillas email, reintentos, DLQ) es un dominio distinto (Supporting) que no debe contaminar el Core Domain.
2. **Acoplamiento cero en runtime:** `ms-credit-evaluation` solo publica un evento en SQS y no conoce la existencia de `ms-notifications`. Si `ms-notifications` cae, las evaluaciones siguen funcionando y los mensajes se acumulan en SQS hasta que el consumer se recupere.
3. **Resiliencia aislada:** Los reintentos, el manejo de DLQ y los fallos de AWS SES están contenidos en `ms-notifications`. Un pico de errores de email no impacta el tiempo de respuesta del endpoint de evaluación.
4. **Base de datos aislada:** `notifications_db` evoluciona de forma independiente. La columna `notificacion_enviada` en `credit_evaluations` es innecesaria gracias al índice único en `notifications.evaluacion_id`.
5. **Escalabilidad diferenciada:** En períodos de alta carga, se pueden escalar instancias de `ms-notifications` sin tocar `ms-credit-evaluation` ni `ms-risk`.

### Consecuencias
- Se agrega un contenedor (`ms-notifications :8083`) y una base de datos (`notifications_db :5434`) al Docker Compose.
- La idempotencia se garantiza vía `UNIQUE INDEX idx_notifications_evaluacion_unique ON notifications(evaluacion_id)`.
- `ms-credit-evaluation` ya no gestiona el estado de notificaciones; elimina la columna `notificacion_enviada`.
- La política IAM se divide: ms-credit-evaluation tiene permisos de publicación en SQS; ms-notifications tiene permisos de consumo SQS y envío SES.

---

## ADR-011: AWS SSM Parameter Store para Gestión de Configuración

**Estado:** Aceptado
**Fecha:** 2026-05-06
**Contexto:** Los `application.properties` de cada microservicio contenían valores de entorno hardcodeados o delegados a variables de entorno de Docker Compose (URLs de bases de datos, credenciales, endpoints de SQS/SES, URLs de Keycloak). Este enfoque dificulta la rotación de credenciales, no diferencia secretos de configuración ordinaria, y requiere reconstruir imágenes o modificar el Compose para cambiar valores entre entornos.

### Opciones evaluadas

| Criterio | Variables de entorno (.env) | HashiCorp Vault | AWS SSM Parameter Store |
|----------|-----------------------------|-----------------|------------------------|
| Jerarquía por servicio | ❌ plano, sin namespace | ✅ rutas arbitrarias | ✅ rutas `/app/servicio/param` |
| Tipos Secret vs String | ❌ todos iguales | ✅ policies + dynamic secrets | ✅ `SecureString` (KMS) vs `String` |
| Emulación local (LocalStack) | ❌ no aplica | ❌ Vault separado | **✅ LocalStack 3.x incluye SSM** |
| Integración Quarkus | ❌ solo env vars | Plugin Vault | **✅ quarkus-config-aws-ssm** |
| Operativo en producción (AWS) | ❌ gestión manual | Infra adicional | **✅ managed service, HA nativo** |
| Auditoría de acceso | ❌ | ✅ | **✅ CloudTrail automático** |
| Rotación de credenciales | ❌ manual | ✅ automática | ✅ con Lambda trigger |
| Curva de aprendizaje | Muy baja | Alta | **Media** |

### Decisión
**AWS SSM Parameter Store** porque:

1. **Paridad local/nube**: LocalStack 3.x emula SSM sin costo adicional de infraestructura. El mismo init script que crea los parámetros localmente crea los equivalentes en AWS real.
2. **Tipado de secretos**: las contraseñas de base de datos se almacenan como `SecureString` (cifradas con KMS en AWS); el resto como `String`. Esta distinción fuerza a tratar los secretos de forma diferente desde el diseño.
3. **Integración nativa Quarkus**: la extensión `quarkus-config-aws-ssm` expone los parámetros SSM como fuente de configuración MicroProfile en tiempo de arranque — sin cambios en el código de negocio.
4. **Eliminación de credenciales del repositorio**: `application.properties` queda con únicamente configuración estructural (tipo de BD, swagger, cors). Ningún valor sensible viaja en el código fuente ni en el Docker Compose.
5. **Jerarquía por servicio**: cada microservicio lee solo su prefijo `/banco/ms-<servicio>/`, sin acceso a los parámetros de otros servicios.

### Consecuencias
- Se agrega `ssm` a los `SERVICES` de LocalStack en docker-compose.
- El `localstack-init.sh` crea todos los parámetros SSM junto con las colas SQS y la identidad SES.
- Cada microservicio agrega la dependencia `quarkus-config-aws-ssm` y configura el prefijo SSM en `application.properties`.
- Los contenedores de microservicios reciben solo `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION` y `AWS_SSM_ENDPOINT` (en local); en AWS estos valores vienen del IAM Role del task.
- Los valores concretos (URLs, credenciales) dejan de existir en `application.properties` comiteado.

> Ver diseño detallado en `docs/11-ssm-config.md` y guía de implementación en `development-plan/09b-ssm-parameter-store.md`.

---

## Resumen de Decisiones

| ID | Decisión | Alternativa Descartada | Razón Principal |
|----|----------|----------------------|-----------------|
| ADR-001 | Quarkus 3.x | Spring Boot | MicroProfile nativo, bajo footprint |
| ADR-002 | REST para ms-credit-evaluation ↔ ms-risk | gRPC | Simplicidad, latencia dominada por mock |
| ADR-003 | PostgreSQL | MongoDB | Modelo relacional, ACID, datos financieros |
| ADR-004 | ~~JWT Stateless~~ → **Supersedido por ADR-010** | — | — |
| ADR-005 | AWS SQS | Llamada directa | Desacoplamiento, resiliencia, DLQ |
| ADR-006 | Módulo 10 custom | Regex simple | Validación matemática real de cédulas |
| ADR-007 | Llamadas paralelas a ms-risk | Secuencial | 43% menos latencia por request |
| ADR-008 | Auth en microservicio independiente (principio) | Auth embebida en ms-credit-evaluation | SRP, aislamiento de BD, cero acoplamiento runtime |
| ADR-009 | Notificaciones en ms-notifications independiente | Worker embebido en ms-credit-evaluation | SRP, resiliencia aislada, escalabilidad diferenciada |
| ADR-010 | Keycloak como IAM | ms-auth propio (SmallRye JWT) | OIDC estándar, sin código de seguridad propio, revocación real, extensibilidad futura |
| ADR-011 | AWS SSM Parameter Store para configuración | Variables de entorno (.env) | Tipado de secretos, paridad local/nube vía LocalStack, auditoría, eliminación de credenciales del repo |
