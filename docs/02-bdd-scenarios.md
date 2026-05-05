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

## Feature: Autenticación y Gestión de Usuarios

```gherkin
Feature: Autenticación y Login
  Como usuario del sistema
  Quiero poder iniciar sesión con mis credenciales
  Para acceder a las funcionalidades según mi rol

  # ──────────────────────────────────────────────
  # LOGIN
  # ──────────────────────────────────────────────

  Scenario: Login exitoso con credenciales válidas
    Given el usuario "analyst@banco.com" existe en el sistema
    And la contraseña del usuario es "SecurePass123!"
    And el usuario tiene rol "ANALYST"
    When se envía POST "/v1/auth/login" con email "analyst@banco.com" y contraseña "SecurePass123!"
    Then la respuesta tiene código HTTP 200
    And la respuesta contiene un campo "accessToken" con un JWT válido
    And el JWT contiene el claim "rol" con valor "ANALYST"
    And el JWT tiene una expiración de 8 horas

  Scenario: Login fallido con contraseña incorrecta
    Given el usuario "analyst@banco.com" existe en el sistema
    When se envía POST "/v1/auth/login" con email "analyst@banco.com" y contraseña "WrongPass"
    Then la respuesta tiene código HTTP 401
    And el campo "error" es "Credenciales inválidas"
    And no se emite ningún token

  Scenario: Login fallido con usuario inexistente
    When se envía POST "/v1/auth/login" con email "noexiste@banco.com" y contraseña "cualquier"
    Then la respuesta tiene código HTTP 401
    And el campo "error" es "Credenciales inválidas"

  Scenario: Acceso denegado a endpoint protegido sin token
    When se envía GET "/v1/credit-evaluations" sin token de autorización
    Then la respuesta tiene código HTTP 401

  Scenario: Acceso denegado a endpoint protegido con token expirado
    Given existe un token JWT expirado
    When se envía GET "/v1/credit-evaluations" con el token expirado
    Then la respuesta tiene código HTTP 401
    And el campo "error" contiene "token expirado"

  Scenario: Acceso denegado cuando el rol no tiene permisos
    Given el usuario "viewer@banco.com" con rol "VIEWER" ha iniciado sesión
    When se envía POST "/v1/credit-evaluations" con el token del VIEWER
    Then la respuesta tiene código HTTP 403
    And el campo "error" contiene "acceso denegado"

  # ──────────────────────────────────────────────
  # GESTIÓN DE USUARIOS
  # ──────────────────────────────────────────────

  Scenario: Admin crea un nuevo usuario ANALYST
    Given el usuario "admin@banco.com" con rol "ADMIN" ha iniciado sesión
    When se envía POST "/v1/auth/users" con:
      """
      {
        "email": "nuevo@banco.com",
        "password": "TempPass456!",
        "nombreCompleto": "Juan Pérez",
        "rol": "ANALYST"
      }
      """
    Then la respuesta tiene código HTTP 201
    And la respuesta contiene el campo "id" con un UUID
    And el campo "email" es "nuevo@banco.com"
    And el campo "rol" es "ANALYST"

  Scenario: No-admin intenta crear un usuario y es rechazado
    Given el usuario "analyst@banco.com" con rol "ANALYST" ha iniciado sesión
    When se envía POST "/v1/auth/users" con datos de nuevo usuario
    Then la respuesta tiene código HTTP 403

  Scenario: Admin actualiza el rol de un usuario
    Given el usuario "admin@banco.com" con rol "ADMIN" ha iniciado sesión
    And existe el usuario con id "user-uuid-123"
    When se envía PUT "/v1/auth/users/user-uuid-123/roles" con rol "VIEWER"
    Then la respuesta tiene código HTTP 200
    And el campo "rol" es "VIEWER"

  Scenario: Admin lista todos los usuarios
    Given el usuario "admin@banco.com" con rol "ADMIN" ha iniciado sesión
    And existen 3 usuarios en el sistema
    When se envía GET "/v1/auth/users"
    Then la respuesta tiene código HTTP 200
    And la respuesta contiene una lista con 3 usuarios

  Scenario: Registro con email duplicado falla
    Given ya existe un usuario con email "analyst@banco.com"
    And el usuario "admin@banco.com" con rol "ADMIN" ha iniciado sesión
    When se envía POST "/v1/auth/users" con email "analyst@banco.com"
    Then la respuesta tiene código HTTP 409
    And el campo "error" contiene "email ya registrado"
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
