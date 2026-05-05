# BDD — Escenarios de Comportamiento (Gherkin)

> Especificaciones ejecutables que describen el comportamiento esperado del sistema desde la perspectiva del negocio.

---

## Feature: Evaluación de Crédito

```gherkin
Feature: Evaluación de Crédito
  Como analista de crédito
  Quiero enviar una solicitud de crédito con los datos del solicitante
  Para obtener una respuesta de aprobación o rechazo basada en reglas de negocio

  Background:
    Given el sistema está operativo
    And el usuario "analyst@banco.com" con rol "ANALYST" ha iniciado sesión
    And el token JWT es válido

  # ──────────────────────────────────────────────
  # ESCENARIOS DE APROBACIÓN
  # ──────────────────────────────────────────────

  Scenario: Crédito aprobado cuando el score es alto y la carga de deuda es baja
    Given el solicitante tiene cédula "1713175071"
    And el monto solicitado es 5000.00 USD
    And el salario mensual es 2000.00 USD
    And el plazo es 3 años
    And el servicio de riesgos retorna un score de 85
    And el servicio de riesgos retorna deudas con mensualidad total de 200.00 USD
    When se envía POST "/v1/credit-evaluations" con los datos del solicitante
    Then la respuesta tiene código HTTP 201
    And el campo "estadoFinal" es "APROBADO"
    And la evaluación es persistida en la base de datos
    And un mensaje es publicado en la cola SQS "credit-evaluation-notifications"

  Scenario: Crédito aprobado en el límite exacto de la regla de negocio
    Given el solicitante tiene cédula "1713175071"
    And el monto solicitado es 1000.00 USD
    And el salario mensual es 2000.00 USD
    And el plazo es 1 año
    And el servicio de riesgos retorna un score de 71
    And el servicio de riesgos retorna deudas con mensualidad total de 0.00 USD
    # cuota nueva + deudaMensual = ~95 < salario * 0.40 = 800
    When se envía POST "/v1/credit-evaluations" con los datos del solicitante
    Then la respuesta tiene código HTTP 201
    And el campo "estadoFinal" es "APROBADO"

  # ──────────────────────────────────────────────
  # ESCENARIOS DE RECHAZO POR SCORE
  # ──────────────────────────────────────────────

  Scenario: Crédito rechazado cuando el score es igual a 70
    Given el solicitante tiene cédula "1713175071"
    And el monto solicitado es 3000.00 USD
    And el salario mensual es 5000.00 USD
    And el plazo es 2 años
    And el servicio de riesgos retorna un score de 70
    And el servicio de riesgos retorna deudas con mensualidad total de 100.00 USD
    When se envía POST "/v1/credit-evaluations" con los datos del solicitante
    Then la respuesta tiene código HTTP 201
    And el campo "estadoFinal" es "RECHAZADO"
    And la evaluación es persistida en la base de datos

  Scenario: Crédito rechazado cuando el score es menor a 70
    Given el solicitante tiene cédula "1713175071"
    And el monto solicitado es 3000.00 USD
    And el salario mensual es 5000.00 USD
    And el plazo es 2 años
    And el servicio de riesgos retorna un score de 45
    And el servicio de riesgos retorna deudas con mensualidad total de 100.00 USD
    When se envía POST "/v1/credit-evaluations" con los datos del solicitante
    Then la respuesta tiene código HTTP 201
    And el campo "estadoFinal" es "RECHAZADO"

  # ──────────────────────────────────────────────
  # ESCENARIOS DE RECHAZO POR CAPACIDAD DE PAGO
  # ──────────────────────────────────────────────

  Scenario: Crédito rechazado cuando la carga de deuda supera el 40% del salario
    Given el solicitante tiene cédula "1713175071"
    And el monto solicitado es 10000.00 USD
    And el salario mensual es 1000.00 USD
    And el plazo es 2 años
    And el servicio de riesgos retorna un score de 90
    And el servicio de riesgos retorna deudas con mensualidad total de 300.00 USD
    # cuota nueva ~450 + deudaMensual 300 = 750 > salario * 0.40 = 400
    When se envía POST "/v1/credit-evaluations" con los datos del solicitante
    Then la respuesta tiene código HTTP 201
    And el campo "estadoFinal" es "RECHAZADO"

  # ──────────────────────────────────────────────
  # ESCENARIOS DE VALIDACIÓN DE ENTRADA
  # ──────────────────────────────────────────────

  Scenario Outline: Rechazo por cédula ecuatoriana inválida
    Given el solicitante tiene cédula "<cedula_invalida>"
    And el monto solicitado es 5000.00 USD
    And el salario mensual es 2000.00 USD
    And el plazo es 2 años
    When se envía POST "/v1/credit-evaluations" con los datos del solicitante
    Then la respuesta tiene código HTTP 422
    And el campo "error" contiene "cédula inválida"
    And no se realiza ninguna llamada al servicio de riesgos

    Examples:
      | cedula_invalida |
      | 1234567890      |
      | 9999999999      |
      | 123             |
      | ABCDEFGHIJ      |
      | 17131750711     |

  Scenario: Rechazo por monto solicitado negativo
    Given el solicitante tiene cédula "1713175071"
    And el monto solicitado es -500.00 USD
    And el salario mensual es 2000.00 USD
    And el plazo es 2 años
    When se envía POST "/v1/credit-evaluations" con los datos del solicitante
    Then la respuesta tiene código HTTP 422
    And el campo "error" contiene "monto debe ser positivo"

  Scenario: Rechazo por salario igual a cero
    Given el solicitante tiene cédula "1713175071"
    And el monto solicitado es 5000.00 USD
    And el salario mensual es 0.00 USD
    And el plazo es 2 años
    When se envía POST "/v1/credit-evaluations" con los datos del solicitante
    Then la respuesta tiene código HTTP 422
    And el campo "error" contiene "salario debe ser positivo"

  # ──────────────────────────────────────────────
  # ESCENARIOS DE RESILIENCIA
  # ──────────────────────────────────────────────

  Scenario: Error manejado cuando el servicio de riesgos no responde
    Given el solicitante tiene cédula "1713175071"
    And el monto solicitado es 5000.00 USD
    And el salario mensual es 2000.00 USD
    And el plazo es 3 años
    And el servicio de riesgos no está disponible
    When se envía POST "/v1/credit-evaluations" con los datos del solicitante
    Then la respuesta tiene código HTTP 503
    And el campo "error" contiene "servicio de riesgos no disponible"
    And no se persiste ninguna evaluación

  # ──────────────────────────────────────────────
  # CONSULTA DE EVALUACIONES
  # ──────────────────────────────────────────────

  Scenario: Listar todas las evaluaciones realizadas
    Given existen 5 evaluaciones en la base de datos
    When se envía GET "/v1/credit-evaluations"
    Then la respuesta tiene código HTTP 200
    And la respuesta contiene una lista con 5 evaluaciones
    And cada evaluación contiene "cedula", "montoSolicitado", "estadoFinal" y "fechaEvaluacion"

  Scenario: Consultar una evaluación por ID
    Given existe una evaluación con id "550e8400-e29b-41d4-a716-446655440000"
    When se envía GET "/v1/credit-evaluations/550e8400-e29b-41d4-a716-446655440000"
    Then la respuesta tiene código HTTP 200
    And el campo "id" es "550e8400-e29b-41d4-a716-446655440000"
```

---

## Feature: Autenticación con Keycloak (OIDC)

```gherkin
Feature: Autenticación via Keycloak OIDC
  Como usuario del sistema
  Quiero iniciar sesión a través de Keycloak
  Para obtener un JWT válido que me permita operar el sistema según mi rol

  # ──────────────────────────────────────────────
  # FLUJO OIDC
  # ──────────────────────────────────────────────

  Scenario: Login exitoso — Keycloak emite JWT con rol correcto
    Given el usuario "analyst@banco.com" existe en el realm "banco" de Keycloak
    And el usuario tiene asignado el realm role "ANALYST"
    When el Frontend completa el flujo Authorization Code + PKCE con Keycloak
    Then Keycloak retorna un access_token JWT firmado con RS256
    And el JWT contiene el claim "groups" con valor ["ANALYST"]
    And el JWT tiene el claim "iss" igual a "http://localhost:9000/realms/banco"
    And el access_token expira en menos de 360 segundos

  Scenario: Keycloak bloquea cuenta tras múltiples intentos fallidos
    Given el usuario "analyst@banco.com" existe en el realm "banco"
    And Keycloak tiene Brute Force Detection activado con umbral de 5 intentos
    When se realizan 5 intentos de login consecutivos con contraseña incorrecta
    Then Keycloak bloquea la cuenta temporalmente
    And cualquier intento adicional retorna error de cuenta bloqueada

  Scenario: ms-credit-evaluation acepta JWT válido de Keycloak
    Given el usuario "analyst@banco.com" obtuvo un JWT válido de Keycloak
    When se envía GET "/v1/credit-evaluations" con el JWT en el header Authorization
    Then la respuesta tiene código HTTP 200

  Scenario: Acceso denegado sin token de autorización
    When se envía GET "/v1/credit-evaluations" sin header Authorization
    Then la respuesta tiene código HTTP 401

  Scenario: Acceso denegado con token expirado
    Given existe un JWT cuyo campo "exp" es anterior al momento actual
    When se envía GET "/v1/credit-evaluations" con el token expirado
    Then la respuesta tiene código HTTP 401

  Scenario: Acceso denegado cuando el rol no tiene permisos suficientes
    Given el usuario "viewer@banco.com" tiene JWT válido con rol "VIEWER"
    When se envía POST "/v1/credit-evaluations" con el JWT del VIEWER
    Then la respuesta tiene código HTTP 403

  # ──────────────────────────────────────────────
  # GESTIÓN DE USUARIOS (Keycloak Admin)
  # ──────────────────────────────────────────────

  Scenario: Admin crea usuario ANALYST via Keycloak Admin REST API
    Given el administrador autenticado en Keycloak Admin tiene permisos de realm-management
    When se envía POST "/admin/realms/banco/users" con email "nuevo@banco.com" y rol "ANALYST"
    Then Keycloak retorna HTTP 201
    And el usuario existe en el realm "banco" con rol "ANALYST"
    And el usuario puede iniciar sesión y obtener un JWT con claim "groups": ["ANALYST"]

  Scenario: Desactivar usuario revoca acceso inmediatamente
    Given el usuario "analyst@banco.com" tiene una sesión activa en Keycloak
    When el administrador desactiva el usuario en Keycloak Admin Console
    Then el refresh token del usuario deja de funcionar
    And cualquier intento de renovar el access_token retorna error
```

---

## Feature: Notificaciones por Email

```gherkin
Feature: Notificaciones de Resultado de Evaluación
  Como solicitante de crédito
  Quiero recibir un email con el resultado de mi evaluación
  Para conocer si mi crédito fue aprobado o rechazado sin tener que consultar el sistema

  Scenario: Notificación de aprobación enviada tras evaluación exitosa
    Given existe una evaluación con id "eval-001" en estado "APROBADO"
    And la evaluación fue realizada para cédula "1713175071"
    And existe un email "solicitante@email.com" asociado a la cédula
    And la cola SQS recibió el mensaje de la evaluación "eval-001"
    When el worker de notificaciones procesa el mensaje de la cola
    Then se envía un email a "solicitante@email.com"
    And el asunto del email contiene "Crédito APROBADO"
    And el cuerpo del email contiene el monto aprobado
    And la notificación queda en estado "ENVIADO" en la base de datos

  Scenario: Notificación de rechazo enviada tras evaluación negativa
    Given existe una evaluación con id "eval-002" en estado "RECHAZADO"
    And la evaluación fue realizada para cédula "0912345678"
    And existe un email "otro@email.com" asociado a la cédula
    And la cola SQS recibió el mensaje de la evaluación "eval-002"
    When el worker de notificaciones procesa el mensaje de la cola
    Then se envía un email a "otro@email.com"
    And el asunto del email contiene "Crédito RECHAZADO"
    And la notificación queda en estado "ENVIADO" en la base de datos

  Scenario: Reintento automático cuando el envío de email falla
    Given existe un mensaje en la cola SQS para evaluación "eval-003"
    And el servicio de email (SES) está temporalmente no disponible
    When el worker de notificaciones intenta procesar el mensaje
    Then el mensaje permanece en la cola SQS para reintento
    And el intento fallido queda registrado
    And después de 3 intentos fallidos el mensaje va a la DLQ

  Scenario: Worker no procesa mensajes duplicados
    Given el mensaje para evaluación "eval-001" ya fue procesado
    And la notificación "eval-001" está en estado "ENVIADO"
    When el worker recibe el mismo mensaje nuevamente (reintento SQS)
    Then el email no se envía de nuevo
    And el mensaje es eliminado de la cola
```

---

## Feature: Servicio de Riesgos (Mock)

```gherkin
Feature: Servicio de Riesgos Mock
  Como microservicio orquestador
  Quiero consultar el score y deudas de un solicitante
  Para tomar la decisión de aprobación o rechazo

  Scenario: Obtener score de riesgo para una cédula válida
    Given el servicio de riesgos está operativo
    When se envía GET "/v1/risk/score/1713175071"
    Then la respuesta tiene código HTTP 200
    And el campo "cedula" es "1713175071"
    And el campo "score" es un número entre 0 y 100
    And la respuesta tarda aproximadamente 2 segundos

  Scenario: Obtener deudas para una cédula válida
    Given el servicio de riesgos está operativo
    When se envía GET "/v1/risk/debts/1713175071"
    Then la respuesta tiene código HTTP 200
    And el campo "cedula" es "1713175071"
    And el campo "deudas" es una lista
    And cada deuda tiene "descripcion" y "mensualidad"
    And la respuesta tarda aproximadamente 1.5 segundos

  Scenario: Score retornado varía entre llamadas (es aleatorio)
    Given el servicio de riesgos está operativo
    When se hacen 10 llamadas GET "/v1/risk/score/1713175071"
    Then al menos 2 respuestas tienen valores de score diferentes
```

---

## Feature: Seguridad — Control de Acceso y Protección de Datos

> Escenarios ejecutables que verifican los controles de seguridad del sistema. Mapeados al Threat Model STRIDE de `10-sdd.md`. Deben ejecutarse en cada PR como parte del pipeline CI/CD.

```gherkin
Feature: Seguridad del Sistema de Evaluación de Créditos
  Como equipo de desarrollo
  Quiero que el sistema rechace activamente intentos de acceso no autorizado y entradas maliciosas
  Para garantizar la confidencialidad, integridad y disponibilidad del sistema

  # ──────────────────────────────────────────────────────────────
  # SR-01: CONTROL DE ACCESO (AuthN / AuthZ)
  # Cubre: STRIDE Spoofing, Elevation of Privilege | OWASP A01, A07
  # ──────────────────────────────────────────────────────────────

  Scenario Outline: Acceso rechazado sin token en cualquier endpoint protegido
    When se envía <metodo> "<endpoint>" sin header Authorization
    Then la respuesta tiene código HTTP 401
    And la respuesta no contiene datos de negocio

    Examples:
      | metodo | endpoint                                                      |
      | GET    | /v1/credit-evaluations                                        |
      | POST   | /v1/credit-evaluations                                        |
      | GET    | /v1/credit-evaluations/550e8400-e29b-41d4-a716-446655440001  |

  Scenario: Token con firma manipulada es rechazado
    Given existe un JWT válido emitido por Keycloak
    And se modifica el payload del JWT para cambiar el rol a "ADMIN"
    And se reensambla el token sin actualizar la firma RS256
    When se envía POST "/v1/credit-evaluations" con el token manipulado
    Then la respuesta tiene código HTTP 401
    And el log de seguridad registra evento "JWT_SIGNATURE_INVALID"

  Scenario: Token con algoritmo "none" es rechazado
    Given se construye un JWT con header {"alg": "none"} y claims de rol "ADMIN"
    When se envía POST "/v1/credit-evaluations" con ese token
    Then la respuesta tiene código HTTP 401

  Scenario: Token emitido por un issuer no confiable es rechazado
    Given existe un JWT firmado por una clave RSA diferente a la de Keycloak realm "banco"
    And el claim "iss" es "http://fake-issuer.com/realms/banco"
    When se envía GET "/v1/credit-evaluations" con ese token
    Then la respuesta tiene código HTTP 401

  Scenario: VIEWER no puede crear evaluaciones (Elevation of Privilege)
    Given el usuario "viewer@banco.com" tiene JWT válido de Keycloak con rol "VIEWER"
    When se envía POST "/v1/credit-evaluations" con el JWT del VIEWER
    Then la respuesta tiene código HTTP 403
    And la evaluación no es persistida en la base de datos
    And ms-risk no recibe ninguna llamada

  Scenario: ANALYST no puede acceder a endpoints de Keycloak Admin
    Given el usuario "analyst@banco.com" tiene JWT válido con rol "ANALYST"
    When se envía GET "/admin/realms/banco/users" con el JWT del ANALYST
    Then Keycloak retorna HTTP 403

  # ──────────────────────────────────────────────────────────────
  # SR-02: INTEGRIDAD DE ENTRADA — Injection y Validación
  # Cubre: STRIDE Tampering | OWASP A03
  # ──────────────────────────────────────────────────────────────

  Scenario Outline: Intento de inyección SQL en campo cédula es rechazado
    Given el usuario "analyst@banco.com" con rol "ANALYST" ha iniciado sesión
    When se envía POST "/v1/credit-evaluations" con cédula "<payload_malicioso>"
    Then la respuesta tiene código HTTP 422
    And el campo "error" contiene "cédula inválida"
    And no se realiza ninguna llamada al servicio de riesgos
    And no se ejecuta ninguna sentencia SQL derivada del input

    Examples:
      | payload_malicioso             |
      | 1' OR '1'='1                  |
      | '; DROP TABLE credit_eval; -- |
      | 1713175071' UNION SELECT 1--  |
      | ${7*7}                        |
      | <script>alert(1)</script>     |

  Scenario Outline: Valores fuera de rango en campos numéricos son rechazados
    Given el usuario "analyst@banco.com" con rol "ANALYST" ha iniciado sesión
    When se envía POST "/v1/credit-evaluations" con <campo> igual a <valor>
    Then la respuesta tiene código HTTP 422
    And el campo "error" contiene descripción del campo inválido

    Examples:
      | campo           | valor       |
      | montoSolicitado | -1          |
      | montoSolicitado | 0           |
      | salario         | -500        |
      | salario         | 0           |
      | plazoAnios      | 0           |
      | plazoAnios      | 31          |
      | plazoAnios      | 999999      |

  Scenario: Payload JSON malformado retorna 400 sin exponer detalles internos
    Given el usuario "analyst@banco.com" con rol "ANALYST" ha iniciado sesión
    When se envía POST "/v1/credit-evaluations" con body "{ invalid json %%% }"
    Then la respuesta tiene código HTTP 400
    And la respuesta no contiene stack trace ni nombre de clase Java

  Scenario: Campos extra en el request son ignorados silenciosamente
    Given el usuario "analyst@banco.com" con rol "ANALYST" ha iniciado sesión
    When se envía POST "/v1/credit-evaluations" con campos adicionales "adminOverride: true" y "bypassRules: true"
    Then la respuesta procesa solo los campos del schema definido
    And los campos adicionales no afectan el resultado de la evaluación

  # ──────────────────────────────────────────────────────────────
  # SR-04: RATE LIMITING
  # Cubre: STRIDE Denial of Service | OWASP A04
  # ──────────────────────────────────────────────────────────────

  Scenario: Exceso de requests desde el mismo usuario retorna 429
    Given el usuario "analyst@banco.com" con rol "ANALYST" ha iniciado sesión
    When se envían 11 requests POST "/v1/credit-evaluations" en menos de 60 segundos desde el mismo usuario
    Then la respuesta número 11 tiene código HTTP 429
    And la respuesta contiene el header "Retry-After" con el tiempo de espera en segundos

  # ──────────────────────────────────────────────────────────────
  # SR-03 + OWASP A09: LOGGING SIN PII
  # ──────────────────────────────────────────────────────────────

  Scenario: La cédula del solicitante no aparece en plaintext en los logs
    Given el usuario "analyst@banco.com" con rol "ANALYST" ha iniciado sesión
    When se envía POST "/v1/credit-evaluations" con cédula "1713175071"
    Then los logs generados por ms-credit-evaluation no contienen la cadena "1713175071" en plaintext
    And los logs contienen la referencia enmascarada "17131****"

  Scenario: El salario del solicitante no aparece en los logs
    Given el usuario "analyst@banco.com" con rol "ANALYST" ha iniciado sesión
    When se envía POST "/v1/credit-evaluations" con salario "5000.00"
    Then los logs generados no contienen el valor "5000.00" en el campo salario

  # ──────────────────────────────────────────────────────────────
  # IDEMPOTENCIA COMO CONTROL DE SEGURIDAD (SQS)
  # Cubre: STRIDE Tampering / Repudiation | OWASP A08
  # ──────────────────────────────────────────────────────────────

  Scenario: Mensaje SQS con evaluacionId ya procesado no genera segundo email
    Given la evaluación "eval-001" fue procesada y la notificación está en estado "ENVIADO"
    When el worker de ms-notifications recibe el mismo mensaje SQS para "eval-001"
    Then no se envía un segundo email al solicitante
    And el índice único en notifications.evaluacion_id garantiza la idempotencia
    And el mensaje es eliminado de la cola sin error

  Scenario: Mensaje SQS con schema inválido es rechazado sin procesar
    Given existe un mensaje en la cola SQS con body malformado (sin campo "evaluacionId")
    When el worker de ms-notifications intenta procesar el mensaje
    Then el mensaje no genera envío de email
    And el error queda registrado en el log de ms-notifications
    And el mensaje es reencolado para reintento (no se elimina hasta maxReceiveCount)

  # ──────────────────────────────────────────────────────────────
  # OWASP A05: SECURITY MISCONFIGURATION — Cabeceras HTTP
  # ──────────────────────────────────────────────────────────────

  Scenario: Las respuestas de ms-credit-evaluation incluyen cabeceras de seguridad
    When se envía cualquier request a ms-credit-evaluation
    Then la respuesta contiene el header "X-Content-Type-Options" con valor "nosniff"
    And la respuesta contiene el header "X-Frame-Options" con valor "DENY"
    And la respuesta no expone el header "Server" con información de versión

  Scenario: CORS rechaza requests desde orígenes no permitidos
    When se envía una request con header "Origin: http://evil.com" a ms-credit-evaluation
    Then la respuesta no contiene el header "Access-Control-Allow-Origin: http://evil.com"
    And la respuesta tiene código HTTP 403
```
