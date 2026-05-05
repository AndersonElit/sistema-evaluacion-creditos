# OpenAPI / Swagger — Especificación de APIs

> Especificación OpenAPI 3.0.3 para todos los endpoints del sistema.  
> Importable directamente en Swagger UI o Postman.

---

## ms-credit-evaluation (`localhost:8080`)

```yaml
openapi: 3.0.3
info:
  title: API de Evaluación de Créditos — Orquestador
  description: |
    API del microservicio orquestador de evaluaciones de crédito.
    Todos los endpoints requieren Bearer JWT emitido por el ms-auth (Auth).
    La autenticación y gestión de usuarios se realiza exclusivamente en ms-auth (:8082).
  version: 1.0.0
  contact:
    name: Equipo de Créditos
    email: dev@banco.com

servers:
  - url: http://localhost:8080
    description: Desarrollo local
  - url: https://api.banco.com
    description: Producción

security:
  - BearerAuth: []

tags:
  - name: Evaluaciones de Crédito
    description: Creación y consulta de evaluaciones crediticias

# ─────────────────────────────────────────────────────────────
# PATHS
# ─────────────────────────────────────────────────────────────
paths:

  # ── EVALUACIONES DE CRÉDITO ───────────────────────────────
  /v1/credit-evaluations:
    post:
      tags: [Evaluaciones de Crédito]
      summary: Crear evaluación de crédito
      description: |
        Evalúa una solicitud de crédito:
        1. Valida cédula ecuatoriana (Módulo 10)
        2. Consulta score y deudas al ms-risk (en paralelo)
        3. Aplica regla: APROBADO si score > 70 Y (deudaMensual + cuotaNueva) < salario * 0.40
        4. Persiste el resultado
        5. Publica evento en SQS para notificación
        
        Requiere rol ADMIN o ANALYST.
      security:
        - BearerAuth: [ADMIN, ANALYST]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/SolicitudCreditoRequest'
            example:
              cedula: "1713175071"
              montoSolicitado: 5000.00
              plazoAnios: 3
              salario: 2000.00
      responses:
        '201':
          description: Evaluación completada
          headers:
            Location:
              schema:
                type: string
              description: URL del recurso creado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/EvaluacionCreditoResponse'
              example:
                id: "550e8400-e29b-41d4-a716-446655440001"
                cedula: "1713175071"
                montoSolicitado: 5000.00
                plazoAnios: 3
                salario: 2000.00
                scoreRiesgo: 85
                deudaMensualTotal: 200.00
                estadoFinal: "APROBADO"
                fechaEvaluacion: "2026-05-05T14:30:00Z"
                evaluadoPor: "analyst@banco.com"
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '422':
          $ref: '#/components/responses/ValidationError'
        '503':
          $ref: '#/components/responses/ServiceUnavailable'

    get:
      tags: [Evaluaciones de Crédito]
      summary: Listar evaluaciones
      description: |
        Retorna la lista paginada de evaluaciones.
        ADMIN y ANALYST ven todas; VIEWER también puede ver pero no evaluar.
      parameters:
        - name: page
          in: query
          schema:
            type: integer
            default: 0
            minimum: 0
        - name: size
          in: query
          schema:
            type: integer
            default: 20
            maximum: 100
        - name: estadoFinal
          in: query
          schema:
            type: string
            enum: [APROBADO, RECHAZADO, PENDIENTE]
        - name: cedulaContiene
          in: query
          schema:
            type: string
      responses:
        '200':
          description: Lista paginada de evaluaciones
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/EvaluacionesPaginadas'
        '401':
          $ref: '#/components/responses/Unauthorized'

  /v1/credit-evaluations/{evaluacionId}:
    get:
      tags: [Evaluaciones de Crédito]
      summary: Obtener evaluación por ID
      parameters:
        - $ref: '#/components/parameters/EvaluacionId'
      responses:
        '200':
          description: Evaluación encontrada
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/EvaluacionCreditoResponse'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '404':
          $ref: '#/components/responses/NotFound'

# ─────────────────────────────────────────────────────────────
# COMPONENTS
# ─────────────────────────────────────────────────────────────
components:

  securitySchemes:
    BearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
      description: |
        JWT emitido por POST /v1/auth/login en el ms-auth (localhost:8082).
        Claims incluidos: sub (email), groups (rol), exp, iat, userId, nombreCompleto.
        ms-credit-evaluation valida la firma con la clave pública RSA de ms-auth.

  parameters:
    EvaluacionId:
      name: evaluacionId
      in: path
      required: true
      schema:
        type: string
        format: uuid

  schemas:

    # ── EVALUACIONES ────────────────────────────────────────
    SolicitudCreditoRequest:
      type: object
      required: [cedula, montoSolicitado, plazoAnios, salario]
      properties:
        cedula:
          type: string
          pattern: '^\d{10}$'
          description: Cédula ecuatoriana de 10 dígitos, validada con Módulo 10
          example: "1713175071"
        montoSolicitado:
          type: number
          format: double
          minimum: 0.01
          example: 5000.00
        plazoAnios:
          type: integer
          minimum: 1
          maximum: 30
          example: 3
        salario:
          type: number
          format: double
          minimum: 0.01
          example: 2000.00

    EvaluacionCreditoResponse:
      type: object
      properties:
        id:
          type: string
          format: uuid
        cedula:
          type: string
        montoSolicitado:
          type: number
          format: double
        plazoAnios:
          type: integer
        salario:
          type: number
          format: double
        scoreRiesgo:
          type: integer
          minimum: 0
          maximum: 100
        deudaMensualTotal:
          type: number
          format: double
        estadoFinal:
          type: string
          enum: [APROBADO, RECHAZADO, PENDIENTE]
        fechaEvaluacion:
          type: string
          format: date-time
        evaluadoPor:
          type: string
          description: Email del usuario que realizó la evaluación

    EvaluacionesPaginadas:
      type: object
      properties:
        contenido:
          type: array
          items:
            $ref: '#/components/schemas/EvaluacionCreditoResponse'
        paginaActual:
          type: integer
        totalPaginas:
          type: integer
        totalElementos:
          type: integer
        tamanoPagina:
          type: integer

    # ── ERRORES ─────────────────────────────────────────────
    ErrorResponse:
      type: object
      properties:
        timestamp:
          type: string
          format: date-time
        status:
          type: integer
        error:
          type: string
        message:
          type: string
        path:
          type: string
        detalles:
          type: array
          items:
            type: object
            properties:
              campo:
                type: string
              mensaje:
                type: string

  responses:
    Unauthorized:
      description: Token ausente, inválido o expirado
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
          example:
            timestamp: "2026-05-05T14:00:00Z"
            status: 401
            error: "Unauthorized"
            message: "Token inválido o expirado"
            path: "/v1/credit-evaluations"

    Forbidden:
      description: El usuario no tiene el rol requerido
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
          example:
            status: 403
            error: "Forbidden"
            message: "Acceso denegado: rol insuficiente"

    NotFound:
      description: Recurso no encontrado
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'

    Conflict:
      description: El recurso ya existe (ej. email duplicado)
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'

    ValidationError:
      description: Error de validación en los datos de entrada
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
          example:
            status: 422
            error: "Unprocessable Entity"
            message: "Error de validación"
            detalles:
              - campo: "cedula"
                mensaje: "Cédula ecuatoriana inválida (Módulo 10 fallido)"

    ServiceUnavailable:
      description: Servicio externo (Riesgos) no disponible
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
          example:
            status: 503
            error: "Service Unavailable"
            message: "El servicio de riesgos no está disponible. Intente más tarde."
```

---

## ms-auth (`localhost:8082`)

```yaml
openapi: 3.0.3
info:
  title: API de Identidad y Acceso — Auth
  description: |
    Microservicio independiente de autenticación y gestión de usuarios.
    Emite JWT firmados con RS256 consumidos por el resto del sistema.
    El endpoint /v1/auth/login es público. El resto requiere rol ADMIN.
  version: 1.0.0

servers:
  - url: http://localhost:8082
    description: Desarrollo local
  - url: https://auth.banco.com
    description: Producción

security:
  - BearerAuth: []

tags:
  - name: Autenticación
    description: Login y emisión de JWT
  - name: Usuarios
    description: Gestión de usuarios y roles (solo ADMIN)
  - name: Claves
    description: Distribución de clave pública RSA

paths:

  # ── AUTH ──────────────────────────────────────────────────
  /v1/auth/login:
    post:
      tags: [Autenticación]
      summary: Iniciar sesión
      description: Autentica al usuario y retorna un JWT válido por 8 horas.
      security: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/LoginRequest'
            example:
              email: "analyst@banco.com"
              password: "SecurePass123!"
      responses:
        '200':
          description: Login exitoso
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/LoginResponse'
              example:
                accessToken: "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
                tokenType: "Bearer"
                expiresIn: 28800
                usuario:
                  id: "550e8400-e29b-41d4-a716-446655440000"
                  email: "analyst@banco.com"
                  nombreCompleto: "María Pérez"
                  rol: "ANALYST"
        '401':
          $ref: '#/components/responses/Unauthorized'
        '422':
          $ref: '#/components/responses/ValidationError'

  # ── CLAVE PÚBLICA ─────────────────────────────────────────
  /v1/auth/public-key:
    get:
      tags: [Claves]
      summary: Obtener clave pública RSA
      description: |
        Retorna la clave pública RSA en formato PEM.
        Usada por otros microservicios para verificar la firma de los JWT.
        Endpoint público, sin autenticación requerida.
      security: []
      responses:
        '200':
          description: Clave pública en formato PEM
          content:
            application/json:
              schema:
                type: object
                properties:
                  publicKey:
                    type: string
                    description: Clave pública RSA en formato PEM
              example:
                publicKey: "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkq..."

  # ── USUARIOS ──────────────────────────────────────────────
  /v1/auth/users:
    get:
      tags: [Usuarios]
      summary: Listar todos los usuarios
      description: Retorna la lista de usuarios registrados. Solo ADMIN.
      security:
        - BearerAuth: [ADMIN]
      responses:
        '200':
          description: Lista de usuarios
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/UsuarioResponse'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'

    post:
      tags: [Usuarios]
      summary: Crear nuevo usuario
      description: Registra un nuevo usuario en el sistema. Solo ADMIN.
      security:
        - BearerAuth: [ADMIN]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CrearUsuarioRequest'
            example:
              email: "nuevo.analista@banco.com"
              password: "TempPass456!"
              nombreCompleto: "Carlos García"
              rol: "ANALYST"
      responses:
        '201':
          description: Usuario creado exitosamente
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UsuarioResponse'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '409':
          $ref: '#/components/responses/Conflict'
        '422':
          $ref: '#/components/responses/ValidationError'

  /v1/auth/users/{userId}:
    get:
      tags: [Usuarios]
      summary: Obtener usuario por ID
      security:
        - BearerAuth: [ADMIN]
      parameters:
        - $ref: '#/components/parameters/UserId'
      responses:
        '200':
          description: Usuario encontrado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UsuarioResponse'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '404':
          $ref: '#/components/responses/NotFound'

  /v1/auth/users/{userId}/roles:
    put:
      tags: [Usuarios]
      summary: Actualizar rol de un usuario
      description: Cambia el rol asignado a un usuario. Solo ADMIN.
      security:
        - BearerAuth: [ADMIN]
      parameters:
        - $ref: '#/components/parameters/UserId'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ActualizarRolRequest'
            example:
              rol: "VIEWER"
      responses:
        '200':
          description: Rol actualizado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UsuarioResponse'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '404':
          $ref: '#/components/responses/NotFound'

components:

  securitySchemes:
    BearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
      description: JWT emitido por este mismo servicio (POST /v1/auth/login).

  parameters:
    UserId:
      name: userId
      in: path
      required: true
      schema:
        type: string
        format: uuid

  schemas:

    LoginRequest:
      type: object
      required: [email, password]
      properties:
        email:
          type: string
          format: email
          example: "analyst@banco.com"
        password:
          type: string
          minLength: 8
          example: "SecurePass123!"

    LoginResponse:
      type: object
      properties:
        accessToken:
          type: string
          description: JWT firmado con RS256
        tokenType:
          type: string
          default: "Bearer"
        expiresIn:
          type: integer
          description: Segundos hasta expiración (28800 = 8 horas)
        usuario:
          $ref: '#/components/schemas/UsuarioResponse'

    CrearUsuarioRequest:
      type: object
      required: [email, password, nombreCompleto, rol]
      properties:
        email:
          type: string
          format: email
        password:
          type: string
          minLength: 8
          description: "Mínimo 8 caracteres, 1 mayúscula, 1 número y 1 símbolo"
        nombreCompleto:
          type: string
          minLength: 3
          maxLength: 100
        rol:
          $ref: '#/components/schemas/Rol'

    UsuarioResponse:
      type: object
      properties:
        id:
          type: string
          format: uuid
        email:
          type: string
          format: email
        nombreCompleto:
          type: string
        rol:
          $ref: '#/components/schemas/Rol'
        activo:
          type: boolean
        creadoEn:
          type: string
          format: date-time

    ActualizarRolRequest:
      type: object
      required: [rol]
      properties:
        rol:
          $ref: '#/components/schemas/Rol'

    Rol:
      type: string
      enum: [ADMIN, ANALYST, VIEWER]

    ErrorResponse:
      type: object
      properties:
        timestamp:
          type: string
          format: date-time
        status:
          type: integer
        error:
          type: string
        message:
          type: string
        path:
          type: string

  responses:
    Unauthorized:
      description: Token ausente, inválido o expirado
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
    Forbidden:
      description: El usuario no tiene el rol requerido
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
    NotFound:
      description: Recurso no encontrado
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
    Conflict:
      description: El recurso ya existe (ej. email duplicado)
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
    ValidationError:
      description: Error de validación en los datos de entrada
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/ErrorResponse'
```

---

## ms-risk (`localhost:8081`)

```yaml
openapi: 3.0.3
info:
  title: API de Riesgos Mock
  description: |
    Servicio de riesgos que retorna score y deudas aleatorias por cédula.
    Simula latencia interna de 2s (score) y 1.5s (deudas).
    No requiere autenticación (servicio interno).
  version: 1.0.0

servers:
  - url: http://localhost:8081
    description: Desarrollo local

tags:
  - name: Riesgos
    description: Endpoints de consulta de riesgo crediticio

paths:

  /v1/risk/score/{cedula}:
    get:
      tags: [Riesgos]
      summary: Obtener score de riesgo por cédula
      description: |
        Retorna un score aleatorio entre 0 y 100 para la cédula dada.
        **Simula una latencia de 2 segundos.**
      parameters:
        - name: cedula
          in: path
          required: true
          schema:
            type: string
            pattern: '^\d{10}$'
          example: "1713175071"
      responses:
        '200':
          description: Score retornado exitosamente
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ScoreResponse'
              example:
                cedula: "1713175071"
                score: 82
                timestamp: "2026-05-05T14:30:02Z"
        '400':
          description: Formato de cédula inválido
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponseRisk'

  /v1/risk/debts/{cedula}:
    get:
      tags: [Riesgos]
      summary: Obtener deudas por cédula
      description: |
        Retorna una lista aleatoria de deudas activas del titular.
        **Simula una latencia de 1.5 segundos.**
        Puede retornar entre 0 y 5 deudas con mensualidades aleatorias.
      parameters:
        - name: cedula
          in: path
          required: true
          schema:
            type: string
            pattern: '^\d{10}$'
          example: "1713175071"
      responses:
        '200':
          description: Lista de deudas retornada exitosamente
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/DeudasResponse'
              example:
                cedula: "1713175071"
                deudas:
                  - id: "deuda-001"
                    descripcion: "Tarjeta de crédito Banco X"
                    mensualidad: 150.00
                  - id: "deuda-002"
                    descripcion: "Préstamo personal"
                    mensualidad: 75.00
                totalMensual: 225.00
                timestamp: "2026-05-05T14:30:01.500Z"
        '400':
          description: Formato de cédula inválido

components:
  schemas:

    ScoreResponse:
      type: object
      properties:
        cedula:
          type: string
        score:
          type: integer
          minimum: 0
          maximum: 100
        timestamp:
          type: string
          format: date-time

    DeudasResponse:
      type: object
      properties:
        cedula:
          type: string
        deudas:
          type: array
          items:
            $ref: '#/components/schemas/Deuda'
        totalMensual:
          type: number
          format: double
          description: Suma de todas las mensualidades
        timestamp:
          type: string
          format: date-time

    Deuda:
      type: object
      properties:
        id:
          type: string
        descripcion:
          type: string
        mensualidad:
          type: number
          format: double
          minimum: 0

    ErrorResponseRisk:
      type: object
      properties:
        error:
          type: string
        message:
          type: string
```

---

## Notas de Implementación en Quarkus

```java
// Configuración SmallRye OpenAPI en application.properties (cada microservicio)
mp.openapi.extensions.smallrye.info.version=1.0.0
quarkus.swagger-ui.always-include=true
quarkus.swagger-ui.path=/swagger-ui

// Acceder en desarrollo:
// Orquestador (ms-credit-evaluation): http://localhost:8080/swagger-ui
// Riesgos     (ms-risk): http://localhost:8081/swagger-ui
// Auth        (ms-auth): http://localhost:8082/swagger-ui
// OpenAPI JSON ms-credit-evaluation:  http://localhost:8080/q/openapi
// OpenAPI JSON ms-auth:  http://localhost:8082/q/openapi
```
