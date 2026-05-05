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

## ADR-002: REST vs gRPC para comunicación Microservicio A ↔ Microservicio B

**Estado:** Aceptado  
**Fecha:** 2026-05-05  
**Contexto:** MS-A (Orquestador) necesita consultar a MS-B (Riesgos) para obtener score y deudas. Debemos elegir el protocolo de comunicación.

### Análisis

#### REST (HTTP/1.1 + JSON)
**Ventajas:**
- Legible y depurable (curl, Postman, logs)
- Soporte nativo en todos los lenguajes y herramientas
- MicroProfile REST Client en Quarkus: anotaciones declarativas sin código de red
- Swagger UI para documentación automática
- Compatible con el Frontend si en el futuro MS-B fuera expuesto directamente

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
**REST** para MS-A ↔ MS-B por las siguientes razones:

1. **El cuello de botella es la latencia simulada** (2s/1.5s), no la serialización. gRPC no reduciría el tiempo total perceptiblemente.
2. **Simplicidad del contrato**: el servicio de riesgos expone solo 2 endpoints simples con payloads pequeños.
3. **Paralelismo suficiente con REST**: `MicroProfile REST Client` + `CompletableFuture.allOf()` permite llamadas paralelas que reducen la latencia de 3.5s a 2s.
4. **Consistencia**: toda la comunicación del sistema usa REST/JSON, facilitando debugging y onboarding.

> **Si el volumen de transacciones escalara a >10,000 req/s** o si MS-B expusiera streaming de datos, la decisión debería revisarse a favor de gRPC.

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

**Estado:** Aceptado  
**Fecha:** 2026-05-05  
**Contexto:** El sistema requiere autenticación, autorización por roles y gestión de usuarios.

### Opciones evaluadas

| Criterio | Keycloak | JWT Stateless (SmallRye) |
|----------|:---:|:---:|
| Complejidad de deploy | Alta (servicio extra) | **Baja (embebido en MS-A)** |
| Gestión de usuarios | ✅ Completa (UI admin) | Manual (endpoints propios) |
| SSO / Federación LDAP | ✅ | ❌ |
| Revocación inmediata | ✅ | ❌ (mitigable con blacklist) |
| Para este proyecto | Overkill | **Suficiente** |
| Curva de aprendizaje | Alta | **Baja** |

### Decisión
**SmallRye JWT stateless** porque:
- Keycloak agrega un componente de infraestructura complejo innecesario para este alcance
- El sistema tiene usuarios internos (no SSO con Google/LDAP)
- 3 roles simples (ADMIN, ANALYST, VIEWER) no justifican un IAM externo
- JWT con RS256 es seguro y estándar para microservicios

> **Migración futura a Keycloak** sería directa: cambiar `mp.jwt.verify.publickey.location` al JWKS de Keycloak y actualizar los claims de roles.

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

## ADR-007: Llamadas paralelas a MS-B

**Estado:** Aceptado  
**Fecha:** 2026-05-05  
**Contexto:** MS-A necesita consultar el score (2s) y las deudas (1.5s) de forma eficiente.

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

Si MS-B falla repetidamente, el Circuit Breaker abre y se retorna 503 al cliente en lugar de agotar threads esperando timeouts.

---

---

## ADR-008: Desacoplamiento de Identidad en Microservicio Independiente (MS-C)

**Estado:** Aceptado  
**Fecha:** 2026-05-05  
**Contexto:** Originalmente, la autenticación y gestión de usuarios estaba embebida en el Microservicio A (Orquestador). Se evaluó si extraer esta responsabilidad a un servicio propio aportaba beneficios reales dado el tamaño del sistema.

### Opciones evaluadas

| Criterio | Auth embebida en MS-A | Auth en MS-C independiente |
|----------|:---:|:---:|
| Responsabilidad única (SRP) | ❌ MS-A mezcla dominios | ✅ cada servicio tiene un propósito |
| Escalabilidad independiente | ❌ escala todo junto | ✅ MS-C puede escalar por separado |
| Reutilización futura | ❌ acoplado a MS-A | ✅ otros servicios pueden consumir JWT de MS-C |
| Aislamiento de BD | ❌ usuarios y evaluaciones en la misma BD | ✅ `auth_db` separada de `creditos_db` |
| Acoplamiento en runtime | — | ✅ cero: MS-A solo necesita la clave pública RSA |
| Complejidad operacional | Baja (1 servicio menos) | Media (un contenedor más) |
| Riesgo de despliegue | Bajo | Bajo (MS-C no es una dependencia runtime de MS-A) |

### Decisión
**Microservicio C independiente** por las siguientes razones:

1. **Separación de responsabilidades:** El orquestador de créditos no debe conocer ni gestionar credenciales de usuarios. Son dominios distintos (Core vs Generic).
2. **Acoplamiento cero en runtime:** Gracias a JWT firmado con RS256, MS-A verifica tokens con la clave pública sin necesitar llamadas HTTP a MS-C. Si MS-C cae, MS-A sigue funcionando para tokens ya emitidos.
3. **Base de datos aislada:** `auth_db` y `creditos_db` evolucionan de forma independiente. No hay FKs cruzadas — `evaluado_por_id` en `credit_evaluations` es una referencia débil por UUID.
4. **Ruta de migración clara:** Si en el futuro se adopta Keycloak o un IdP externo, solo se reemplaza MS-C sin tocar MS-A ni MS-B. Solo cambia `mp.jwt.verify.publickey.location`.

### Consecuencias
- El frontend hace dos tipos de llamadas: a MS-C para autenticarse y a MS-A para operar.
- La clave pública RSA debe estar disponible en el build de MS-A (como archivo PEM) o descargarse de `GET /v1/auth/public-key` de MS-C durante el arranque.
- Se agrega un contenedor al Docker Compose de desarrollo.

---

## Resumen de Decisiones

| ID | Decisión | Alternativa Descartada | Razón Principal |
|----|----------|----------------------|-----------------|
| ADR-001 | Quarkus 3.x | Spring Boot | MicroProfile nativo, bajo footprint |
| ADR-002 | REST para A↔B | gRPC | Simplicidad, latencia dominada por mock |
| ADR-003 | PostgreSQL | MongoDB | Modelo relacional, ACID, datos financieros |
| ADR-004 | JWT Stateless | Keycloak | Sin overhead de servicio externo |
| ADR-005 | AWS SQS | Llamada directa | Desacoplamiento, resiliencia, DLQ |
| ADR-006 | Módulo 10 custom | Regex simple | Validación matemática real de cédulas |
| ADR-007 | Llamadas paralelas | Secuencial | 43% menos latencia por request |
| ADR-008 | Auth en MS-C independiente | Auth embebida en MS-A | SRP, aislamiento de BD, cero acoplamiento runtime |
