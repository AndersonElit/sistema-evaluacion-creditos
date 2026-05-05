# Security-Driven Development (SDD)

> La seguridad no es una fase final — es una restricción de diseño que guía cada decisión desde el primer día. SDD integra modelado de amenazas, controles verificables y pruebas de seguridad en el mismo ciclo que las funcionalidades de negocio.

---

## 1. Principios SDD Aplicados al Proyecto

| Principio | Aplicación concreta |
|-----------|-------------------|
| **Security by Design** | Keycloak como IAM externo eliminó código de autenticación propio — el riesgo se reduce al no escribir código de seguridad ad-hoc |
| **Least Privilege** | Políticas IAM separadas por servicio; roles RBAC con permisos mínimos; ms-risk sin acceso público |
| **Defense in Depth** | JWT RS256 + RBAC + Bean Validation + Panache PreparedStatements — capas independientes |
| **Fail Secure** | Tokens inválidos → 401. Rol insuficiente → 403. ms-risk caído → 503 (no evalúa con datos parciales) |
| **No Security through Obscurity** | Controles documentados y verificables; JWKS público — la seguridad no depende de ocultar mecanismos |
| **Audit Everything** | `evaluado_por_id` + `fecha_evaluacion` en cada evaluación; estado de notificación trazable por `mensaje_sqs_id` |

---

## 2. Modelo de Amenazas — STRIDE

### 2.1 Superficies de Ataque

```
 [Browser/Frontend]
      │ OIDC/PKCE                │ REST + Bearer JWT
      ▼                          ▼
 [Keycloak :9000]        [ms-credit-evaluation :8080]
                                 │ HTTP (interno)  │ JDBC
                                 ▼                 ▼
                           [ms-risk :8081]   [creditos_db]
                                 │ AWS SDK
                                 ▼
                           [AWS SQS]
                                 │ Polling
                                 ▼
                    [ms-notifications :8083]
                          │ AWS SDK  │ JDBC
                          ▼          ▼
                      [AWS SES]  [notifications_db]
```

---

### 2.2 Análisis STRIDE por Servicio

#### ms-credit-evaluation (superficie principal)

| Categoría | Amenaza | Control Implementado | Estado |
|-----------|---------|---------------------|--------|
| **S** Spoofing | JWT forjado o robado para suplantar un analista | JWKS RS256 — Keycloak firma con clave privada no expuesta | ✅ Cubierto |
| **S** Spoofing | Llamada directa desde internet sin autenticación | `@RolesAllowed` + `@Authenticated` en todos los endpoints | ✅ Cubierto |
| **T** Tampering | Inyección SQL via cédula, monto o salario | Hibernate Panache — solo `PreparedStatements` con parámetros nombrados | ✅ Cubierto |
| **T** Tampering | Manipulación del body de request (valores negativos, nulos) | `@Valid` + Bean Validation en `SolicitudCreditoRequest` | ✅ Cubierto |
| **T** Tampering | Modificación de evaluación ya persistida via API | No existe endpoint PUT/PATCH en evaluaciones — solo POST + GET | ✅ Cubierto |
| **R** Repudiation | No identificar quién creó qué evaluación | `evaluado_por_id` (sub claim del JWT) + `fecha_evaluacion` en cada fila | ✅ Cubierto |
| **R** Repudiation | Ausencia de audit log detallado | Solo se almacena el resultado, no el trail de pasos intermedios | ⚠️ Gap parcial |
| **I** Info Disclosure | Stack traces en respuestas de error | `ExceptionMapper` global — errores genéricos al cliente, detalle solo en logs | ✅ Cubierto |
| **I** Info Disclosure | Cédula y salario en plaintext en logs | No hay enmascaramiento de PII en logs actualmente | ❌ Gap |
| **I** Info Disclosure | Datos de evaluaciones de otros usuarios (IDOR) | No hay filtro por `evaluado_por_id` en GET — todos los autenticados ven todo | ⚠️ Gap (decisión de negocio) |
| **D** Denial of Service | Flood de POST /v1/credit-evaluations | No hay rate limiting implementado | ❌ Gap |
| **D** Denial of Service | Circuit Breaker abierto por flood a ms-risk | SmallRye Fault Tolerance: Circuit Breaker + Timeout 5s | ✅ Cubierto |
| **E** Elevation | VIEWER invoca POST /v1/credit-evaluations | `@RolesAllowed({"ADMIN", "ANALYST"})` rechaza con 403 | ✅ Cubierto |
| **E** Elevation | Analista modifica su propio JWT para cambiar rol | JWT firmado RS256 — modificación invalida la firma | ✅ Cubierto |

#### ms-risk (servicio interno)

| Categoría | Amenaza | Control Implementado | Estado |
|-----------|---------|---------------------|--------|
| **S** Spoofing | Cualquier proceso llama a ms-risk directamente (sin auth) | No tiene autenticación — confía en aislamiento de red | ❌ Gap (mitigable con network policy) |
| **D** Denial of Service | Flood directo a ms-risk desde internet | Depende del aislamiento de red/Docker network | ⚠️ Gap si no hay segmentación |
| **I** Info Disclosure | Logs de cédulas consultadas | Misma exposición que ms-credit-evaluation | ❌ Gap |

#### ms-notifications (worker asíncrono)

| Categoría | Amenaza | Control Implementado | Estado |
|-----------|---------|---------------------|--------|
| **T** Tampering | Mensaje SQS manipulado (campos falsificados) | Validación de schema al deserializar + idempotencia por `evaluacion_id` | ✅ Cubierto |
| **S** Spoofing | Publicación en SQS por proceso no autorizado | IAM policy: solo ms-credit-evaluation tiene `sqs:SendMessage` | ✅ Cubierto |
| **R** Repudiation | Email enviado sin traza | `mensaje_sqs_id` + `enviado_en` + `estado` en `notifications` | ✅ Cubierto |
| **I** Info Disclosure | PII del solicitante en el cuerpo del email | Email contiene solo monto y fecha — no cédula ni datos bancarios | ✅ Cubierto |
| **D** Denial of Service | DLQ colapsada por mensajes malformados | DLQ retiene hasta 14 días; alarma CloudWatch cuando DLQ > 0 | ✅ Cubierto |

#### Keycloak (IAM externo)

| Categoría | Amenaza | Control Implementado | Estado |
|-----------|---------|---------------------|--------|
| **S** Spoofing | Credential stuffing / brute force en login | Brute Force Detection nativo en Keycloak (configurable) | ✅ Cubierto |
| **S** Spoofing | Intercepción del authorization code (PKCE) | PKCE obligatorio — sin `code_verifier` el code es inútil | ✅ Cubierto |
| **T** Tampering | Token forjado para acceder a ms-credit-evaluation | JWT firmado RS256 — JWKS cacheado valida la firma en ms-credit-evaluation | ✅ Cubierto |
| **I** Info Disclosure | Token de larga duración robado | Access token: 5 min. Refresh token: sesión de Keycloak | ✅ Cubierto |
| **E** Elevation | Admin de Keycloak escala privilegios a nivel de negocio | Roles de Keycloak mapeados 1:1 con permisos de negocio — ADMIN de Keycloak ≠ omnipotencia en BD | ✅ Cubierto |

---

## 3. Matriz de Controles de Seguridad

### Controles Implementados

| Control | Capa | Mecanismo |
|---------|------|-----------|
| Autenticación | API Gateway (ms-credit-evaluation) | JWT RS256 validado via JWKS de Keycloak |
| Autorización | API | `@RolesAllowed`, `@Authenticated` — SmallRye JWT |
| Prevención SQL Injection | Repositorio | Hibernate Panache — `PreparedStatements` exclusivamente |
| Validación de entrada | API | Bean Validation `@Valid`, `@Pattern`, `@Positive`, `@Min`/`@Max` |
| Validación de cédula | Dominio | Algoritmo Módulo 10 como Value Object `Cedula` |
| Encriptación en tránsito | Infraestructura | HTTPS (TLS) para todas las comunicaciones externas |
| Secretos fuera del código | Configuración | Variables de entorno (`${DB_PASSWORD}`, `${AWS_SECRET_ACCESS_KEY}`) |
| Least privilege en AWS | IAM | Políticas separadas por servicio (publish-only / consume-only) |
| Idempotencia | Dominio / DB | `UNIQUE INDEX` en `notifications.evaluacion_id` |
| Errores genéricos al cliente | API | `ExceptionMapper` global — sin stack traces en respuestas |
| Prevención de token theft | OIDC | PKCE obligatorio, access token de 5 minutos |
| Brute force en login | Keycloak | Brute Force Detection nativo |
| Resiliencia a fallos | ms-credit-evaluation | Circuit Breaker + Timeout (SmallRye Fault Tolerance) |
| Trazabilidad de evaluaciones | BD | `evaluado_por_id` (sub JWT) + `fecha_evaluacion` |
| Trazabilidad de notificaciones | BD | `mensaje_sqs_id` + `enviado_en` + `estado` |

### Gaps Identificados (Backlog de Seguridad)

| ID | Gap | Riesgo | Mitigación Propuesta | Prioridad |
|----|-----|--------|---------------------|-----------|
| SEC-01 | Sin rate limiting en POST /v1/credit-evaluations | DoS / abuso de API | Quarkus `quarkus-micrometer` + Bucket4j, o API Gateway throttling | Alta |
| SEC-02 | PII (cédula, salario) en logs sin enmascaramiento | Info Disclosure en logs centralizados | Logger wrapper con enmascaramiento de campos sensibles | Alta |
| SEC-03 | ms-risk sin autenticación (expuesto en red interna) | Spoofing si hay acceso lateral | API Key en header interno, o Docker network isolation + firewall rule | Media |
| SEC-04 | Sin filtro por usuario en GET /v1/credit-evaluations | IDOR — analistas ven evaluaciones ajenas | Filtro opcional por `evaluado_por_id` según rol (configurar como requisito de negocio) | Media |
| SEC-05 | Sin audit log de eventos de seguridad | Repudiation en incidentes | Structured security log: `[SECURITY] event=ACCESS_DENIED user=<sub> path=<path>` | Media |
| SEC-06 | Sin security headers HTTP | Clickjacking, MIME sniffing, XSS | Quarkus Filter: `X-Content-Type-Options`, `X-Frame-Options`, `Strict-Transport-Security` | Baja |
| SEC-07 | Sin validación de dominio de email del destinatario | Email injection en notificaciones | Regex de validación RFC 5321 en `destinatarioEmail` antes de invocar SES | Baja |

---

## 4. Security Requirements

Derivados del modelo de amenazas — son requisitos verificables, no aspiraciones.

### SR-01: Control de Acceso
- Todo endpoint de ms-credit-evaluation debe requerir un JWT válido emitido por Keycloak realm `banco`
- `POST /v1/credit-evaluations` solo accesible con rol `ADMIN` o `ANALYST`
- `GET /v1/credit-evaluations` accesible para cualquier rol autenticado
- Cualquier request sin JWT válido debe retornar **401** — nunca 200 ni 500

### SR-02: Integridad de Datos de Entrada
- El campo `cedula` debe pasar el algoritmo Módulo 10 antes de consultar ms-risk
- `montoSolicitado` y `salario` deben ser estrictamente positivos (`> 0`)
- `plazoAnios` debe estar en el rango `[1, 30]`
- Ningún campo de string debe ejecutarse como expresión (no eval, no JNDI, no EL injection)

### SR-03: Protección de PII en Logs
- `cedula`, `salario`, `monto` y `email` no deben aparecer en plaintext en logs de producción
- Los logs deben usar un formato estructurado con nivel `WARN` o superior para eventos de seguridad

### SR-04: Rate Limiting
- `POST /v1/credit-evaluations`: máximo **10 requests/minuto por usuario** (sub JWT)
- Exceso retorna **429 Too Many Requests** con header `Retry-After`

### SR-05: Aislamiento de Red para ms-risk
- ms-risk no debe ser alcanzable desde fuera de la red Docker/VPC
- Solo ms-credit-evaluation puede llamar a ms-risk (Docker network o security group)

### SR-06: Gestión de Secretos
- Ninguna credencial (DB password, AWS keys, Keycloak admin password) debe existir en el código fuente ni en archivos `.properties` comiteados
- En producción: usar AWS Secrets Manager o Vault
- En desarrollo: variables de entorno en `.env` (excluido de git via `.gitignore`)

### SR-07: Seguridad en Transporte
- Todas las comunicaciones externas deben usar TLS 1.2 mínimo (TLS 1.3 preferido)
- HSTS habilitado en el Frontend (`Strict-Transport-Security: max-age=31536000`)
- Certificados renovados automáticamente (Let's Encrypt / ACM en producción)

---

## 5. Checklist de Código Seguro por Capa

### Capa API (Resources)

```
[ ] Todo endpoint tiene @RolesAllowed o @Authenticated
[ ] Todos los request bodies tienen @Valid
[ ] Las respuestas de error usan tipos genéricos (no exponen causa interna)
[ ] No se logea información del request body completo
[ ] Los IDs en path params son UUID — no se usan IDs secuenciales predecibles
[ ] CORS configurado explícitamente (no wildcard en producción)
```

### Capa Servicio

```
[ ] No hay lógica de autenticación/autorización — eso es responsabilidad de la capa API
[ ] Toda operación sobre datos externos valida el resultado antes de usarlo
[ ] Los errores de servicios externos (ms-risk, SQS) se propagan con mensajes genéricos
[ ] No hay datos sensibles en mensajes de excepción
[ ] Los eventos publicados en SQS contienen solo los datos mínimos necesarios
```

### Capa Repositorio

```
[ ] Solo se usa Hibernate Panache — ningún query con concatenación de strings
[ ] Parámetros siempre nombrados: Parameters.with("cedula", cedula)
[ ] No hay queries nativas con valores interpolados
[ ] Las transacciones están acotadas al scope mínimo necesario
```

### Configuración e Infraestructura

```
[ ] Ninguna credencial en application.properties (usar ${ENV_VAR:default_dev_value})
[ ] .env y *.pem en .gitignore
[ ] Docker Compose no expone puertos innecesarios al host (ms-risk solo accesible internamente)
[ ] Variables de entorno de producción gestionadas por Secrets Manager / Vault
[ ] Imágenes Docker usan usuario no-root (USER 1001 en Dockerfile)
```

---

## 6. Security Testing Strategy

### Pirámide de Testing de Seguridad

```
                    /\
                   /  \
                  / PT \      ← Penetration Testing (manual, trimestral)
                 /------\
                /  DAST  \    ← OWASP ZAP en staging (cada release)
               /----------\
              /    SAST    \   ← SonarQube + SpotBugs (cada PR)
             /--------------\
            / Dep. Scanning  \  ← Trivy + OWASP Dep-Check (cada build)
           /------------------\
          / Security Unit Tests \← JUnit + RestAssured (cada PR)
         /______________________\
```

### 6.1 Security Unit Tests (JUnit + RestAssured)

Escenarios automáticos ejecutados en cada PR — ver sección de BDD en `02-bdd-scenarios.md`:

| Test | Endpoint | Qué verifica |
|------|----------|-------------|
| Sin JWT → 401 | GET, POST /v1/credit-evaluations | AuthN obligatoria |
| JWT expirado → 401 | GET /v1/credit-evaluations | Validación de `exp` claim |
| JWT firma inválida → 401 | POST /v1/credit-evaluations | Integridad del token |
| VIEWER → POST → 403 | POST /v1/credit-evaluations | RBAC correcto |
| Cédula inválida → 422 | POST /v1/credit-evaluations | Input validation |
| SQL injection en cédula → 422 | POST /v1/credit-evaluations | No SQL injection |
| Monto negativo → 422 | POST /v1/credit-evaluations | Bean Validation |
| Mensaje SQS duplicado → idempotente | Worker | No doble envío |

### 6.2 SAST — Análisis Estático

```yaml
# sonar-project.properties
sonar.projectKey=sistema-evaluacion-creditos
sonar.sources=src/main/java
sonar.tests=src/test/java
sonar.java.binaries=target/classes

# Reglas de seguridad habilitadas (OWASP Top 10):
# java:S2076  — OS command injection
# java:S2077  — SQL injection via string formatting
# java:S2078  — LDAP injection
# java:S5131  — XSS via HTTP response
# java:S5145  — Log injection
# java:S5167  — HTTP response splitting
# java:S6096  — Path traversal
```

### 6.3 Dependency Scanning

```bash
# OWASP Dependency Check (Maven plugin)
mvn org.owasp:dependency-check-maven:check \
  -DfailBuildOnCVSS=7 \
  -DsuppressionFiles=owasp-suppressions.xml

# Trivy — escaneo de imagen Docker
trivy image ms-credit-evaluation:latest \
  --severity HIGH,CRITICAL \
  --exit-code 1

# Secret scanning (pre-commit hook o CI)
gitleaks detect --source . --verbose
```

### 6.4 DAST — OWASP ZAP (staging)

```bash
# Escaneo activo contra entorno staging
docker run -t owasp/zap2docker-stable zap-full-scan.py \
  -t http://staging.creditos.banco.com \
  -r zap-report.html \
  -z "-config scanner.attackStrength=HIGH"

# Reglas prioritarias:
# - 10202: Absent Anti-CSRF Tokens
# - 10038: Content Security Policy Header Not Set
# - 10020: Missing Anti-clickjacking Header
# - 40012: Cross Site Scripting (Reflected)
# - 40018: SQL Injection
# - 10094: Base64 Disclosure
```

### 6.5 Penetration Testing (trimestral)

Áreas prioritarias para pentest manual:

| Área | Técnica | Herramienta |
|------|---------|-------------|
| OIDC/PKCE flow | Token replay, code interception | Burp Suite |
| JWT claims | Claim forgery, `alg:none` attack | jwt_tool |
| RBAC | Horizontal privilege escalation (IDOR) | Manual + Postman |
| Input validation | Fuzzing de campos de entrada | ffuf / Burp Intruder |
| SQS messages | Mensaje crafteado con payload malicioso | awscli manual |
| Secrets exposure | `.env`, `.git`, actuator endpoints | gitrob, nuclei |

---

## 7. CI/CD Security Pipeline

```yaml
# .github/workflows/security.yml (ejemplo)
name: Security Gates

on: [push, pull_request]

jobs:
  secret-scan:
    name: Secret Scanning
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - name: GitLeaks
        uses: gitleaks/gitleaks-action@v2

  sast:
    name: SAST — SonarQube
    runs-on: ubuntu-latest
    needs: secret-scan
    steps:
      - uses: actions/checkout@v4
      - name: Build & Analyze
        run: mvn verify sonar:sonar -Dsonar.qualitygate.wait=true

  dependency-check:
    name: Dependency Vulnerabilities
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: OWASP Dependency Check
        run: mvn dependency-check:check -DfailBuildOnCVSS=7

  image-scan:
    name: Docker Image Scan
    runs-on: ubuntu-latest
    needs: sast
    steps:
      - name: Build image
        run: docker build -t app:${{ github.sha }} .
      - name: Trivy scan
        uses: aquasecurity/trivy-action@master
        with:
          image-ref: app:${{ github.sha }}
          severity: HIGH,CRITICAL
          exit-code: '1'

  # Gates: el PR no puede fusionarse si alguno de los jobs anteriores falla
```

### Security Gate — Criterios de Bloqueo de PR

| Gate | Bloquea PR | Condición |
|------|-----------|-----------|
| Secret detectado en código | ✅ Siempre | Cualquier secreto detectado por GitLeaks |
| CVE crítico en dependencia | ✅ Siempre | CVSS ≥ 9.0 sin supresión justificada |
| CVE alto en dependencia | ✅ Siempre | CVSS ≥ 7.0 sin supresión justificada |
| Quality Gate SonarQube fallido | ✅ Siempre | Cobertura < umbral o bug de seguridad nuevo |
| CVE crítico en imagen Docker | ✅ Siempre | Trivy detecta CRITICAL en imagen |
| CVE alto en imagen Docker | ⚠️ Warning | Trivy detecta HIGH — requiere revisión manual |

---

## 8. Security Acceptance Criteria

Una funcionalidad se considera **done** desde la perspectiva de seguridad cuando cumple **todos** los siguientes criterios:

```
[ ] Los escenarios BDD de seguridad relevantes pasan (sin JWT → 401, rol incorrecto → 403, input inválido → 422)
[ ] No existen CVEs CVSS ≥ 7 sin suprimir en las dependencias del servicio modificado
[ ] El análisis SAST no introduce nuevos issues de seguridad tipo Bug o Vulnerability
[ ] No hay secretos en el diff del PR (GitLeaks pasa sin alertas)
[ ] Los logs del servicio no contienen PII en plaintext para los nuevos flujos
[ ] El endpoint nuevo/modificado tiene @RolesAllowed o @Authenticated explícito
[ ] Los datos de entrada del endpoint nuevo/modificado tienen @Valid
[ ] La imagen Docker del servicio no tiene vulnerabilidades CRITICAL
[ ] El Threat Model fue revisado: si la funcionalidad agrega superficie de ataque nueva, el STRIDE fue actualizado
```

---

## 9. Mapeo OWASP Top 10 → Controles del Sistema

| OWASP 2021 | Categoría | Control en este sistema |
|-----------|-----------|------------------------|
| A01 | Broken Access Control | `@RolesAllowed` + `@Authenticated`; Keycloak RBAC; PKCE |
| A02 | Cryptographic Failures | TLS en tránsito; JWT RS256; bcrypt en Keycloak (usuarios) |
| A03 | Injection | Hibernate Panache PreparedStatements; Bean Validation; Módulo 10 |
| A04 | Insecure Design | Threat Model STRIDE; Security Requirements como parte del diseño |
| A05 | Security Misconfiguration | CORS explícito; IAM least privilege; no credenciales en código |
| A06 | Vulnerable Components | OWASP Dependency Check + Trivy en CI/CD |
| A07 | Auth Failures | Keycloak: PKCE, short-lived tokens, brute force protection |
| A08 | Software & Data Integrity | JWT firmado RS256; UNIQUE INDEX idempotencia SQS |
| A09 | Security Logging Failures | `evaluado_por_id` + `fecha_evaluacion`; `mensaje_sqs_id`; **Gap SEC-02 / SEC-05** |
| A10 | SSRF | ms-risk en red interna (Docker network); no hay calls a URLs de usuario |
