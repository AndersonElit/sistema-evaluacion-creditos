# DDD Estratégico — Sistema de Evaluación de Créditos

## 1. Dominio Principal

**Evaluación de Créditos** es el corazón del negocio: determinar si una persona es sujeta de crédito en función de su perfil de riesgo y capacidad de pago.

---

## 2. Subdominios

| Subdominio | Tipo | Descripción |
|------------|------|-------------|
| Evaluación de Crédito | **Core Domain** | Orquestación de la evaluación, reglas de negocio y persistencia |
| Valoración de Riesgos | **Supporting Domain** | Calcula score y deudas a partir de la cédula (mock externo) |
| Identidad y Acceso | **Generic Domain** | Autenticación, autorización y gestión de usuarios/roles |
| Notificaciones | **Supporting Domain** | Comunicación asíncrona del resultado al solicitante |

---

## 3. Bounded Contexts

### 3.1 Bounded Context: Evaluación de Crédito (`credit-evaluation`)

**Responsabilidad:** Recibir solicitudes, orquestar servicios externos, aplicar reglas de negocio y persistir resultados.

#### Lenguaje Ubicuo

| Término | Definición |
|---------|-----------|
| `SolicitudDeCredito` | Petición de un ciudadano para obtener financiamiento |
| `Cedula` | Número de identificación ecuatoriana validado por Módulo 10 |
| `MontoSolicitado` | Valor monetario en USD que el solicitante requiere |
| `PlazoEnAnios` | Duración del crédito expresada en años enteros |
| `Salario` | Ingreso mensual declarado por el solicitante en USD |
| `Evaluacion` | Resultado del proceso: APROBADO o RECHAZADO |
| `CapacidadDePago` | Porcentaje del salario disponible para nueva deuda (máx 40%) |
| `ScoreDeRiesgo` | Puntuación 0–100 obtenida del servicio externo de riesgos |
| `DeudaMensual` | Suma de obligaciones mensuales vigentes del solicitante |
| `ReglaDeAprobacion` | `score > 70 AND (deudaMensual + cuotaNueva) < salario * 0.40` |

#### Agregados y Entidades

```
Aggregate Root: EvaluacionCredito
├── id: UUID
├── cedula: Cedula (Value Object)
├── montoSolicitado: Dinero (Value Object)
├── plazoAnios: int
├── salario: Dinero (Value Object)
├── scoreRiesgo: ScoreRiesgo (Value Object)
├── deudaMensual: Dinero (Value Object)
├── estadoFinal: EstadoEvaluacion (Enum: APROBADO, RECHAZADO, PENDIENTE)
├── fechaEvaluacion: Instant
├── evaluadoPor: UsuarioId (referencia por ID — cross-context)
└── notificacionEnviada: boolean
```

#### Value Objects

```
Cedula
├── valor: String (10 dígitos)
└── validate(): algoritmo Módulo 10 ecuatoriano

Dinero
├── cantidad: BigDecimal
├── moneda: String (default "USD")
└── validate(): cantidad > 0

ScoreRiesgo
├── valor: int (0–100)
└── esSuficiente(): valor > 70

EstadoEvaluacion
└── APROBADO | RECHAZADO | PENDIENTE
```

#### Eventos de Dominio

| Evento | Descripción | Publicado cuando |
|--------|-------------|-----------------|
| `EvaluacionSolicitada` | Nueva solicitud recibida y validada | POST /v1/credit-evaluations exitoso |
| `EvaluacionCompletada` | Resultado calculado y persistido | Regla de negocio evaluada |
| `EvaluacionFallida` | Error al contactar servicio de riesgos | Timeout o error 5xx de ms-risk |

> **Nota:** Este bounded context **no gestiona usuarios ni emite tokens JWT**. Delega completamente la identidad a Keycloak (Bounded Context de Identidad y Acceso). El `evaluadoPor` es solo el `sub` claim del JWT de Keycloak — una referencia débil por UUID, no una consulta a Keycloak en runtime.

---

### 3.2 Bounded Context: Valoración de Riesgos (`risk-assessment`)

**Responsabilidad:** Proveer datos de riesgo simulados (score y deudas) dado un número de cédula.

#### Lenguaje Ubicuo

| Término | Definición |
|---------|-----------|
| `PerfilDeRiesgo` | Conjunto de indicadores de riesgo asociados a una cédula |
| `ScoreCredito` | Valor numérico 0–100 que indica la solvencia del titular |
| `Deuda` | Obligación financiera activa del titular |
| `MensualidadDeuda` | Cuota mensual de una deuda específica |
| `LatenciaSimulada` | Retardo artificial que simula procesamiento real |

#### Agregados

```
Aggregate Root: PerfilDeRiesgo
├── cedula: String
├── scoreCredito: int (0–100, random)
└── deudas: List<Deuda>

Deuda
├── id: UUID
├── descripcion: String
└── mensualidad: BigDecimal
```

---

### 3.3 Bounded Context: Identidad y Acceso (`identity-access`)

**Responsabilidad:** Gestionar usuarios, credenciales, roles y emisión de tokens JWT. Implementado mediante **Keycloak 24.x** como OIDC Provider externo (`localhost:9000`, realm `banco`). El sistema de créditos no desarrolla ni mantiene código de identidad — Keycloak gestiona todo el ciclo de vida de usuarios y tokens.

#### Lenguaje Ubicuo

| Término | Definición |
|---------|-----------|
| `Usuario` | Persona registrada en Keycloak con credenciales válidas |
| `Realm` | Espacio de aislamiento en Keycloak (`banco`) que agrupa usuarios, roles y clientes |
| `Client` | Aplicación registrada en Keycloak (`credit-evaluation-spa` para el Frontend) |
| `Token` | JWT firmado por Keycloak con RS256, válido para acceder a ms-credit-evaluation |
| `Rol` | Realm Role de Keycloak asignado a un usuario (ADMIN, ANALYST, VIEWER) |
| `Sesion` | Sesión OIDC gestionada por Keycloak (access token 5min + refresh token) |
| `JWKS` | JSON Web Key Set — endpoint de Keycloak con las claves públicas RSA para verificación |

#### Roles y Permisos

| Rol | Puede evaluar | Puede ver lista | Puede gestionar usuarios en Keycloak |
|-----|:---:|:---:|:---:|
| `ADMIN` | ✅ | ✅ | ✅ (vía Keycloak Admin Console) |
| `ANALYST` | ✅ | ✅ | ❌ |
| `VIEWER` | ❌ | ✅ | ❌ |

#### Modelo en Keycloak (sin código propio)

```
Keycloak Realm: banco
├── Client: credit-evaluation-spa
│   ├── Protocol: openid-connect
│   ├── Access Type: public (PKCE, sin client secret)
│   └── Valid Redirect URIs: http://localhost:3000/*
├── Realm Roles: ADMIN | ANALYST | VIEWER
├── Mapper: roles → claim "groups" (compatibilidad SmallRye JWT)
└── Users: gestionados vía Admin Console / Admin REST API
```

> No existen agregados DDD propios para este contexto — Keycloak es el sistema de registro. El `sub` claim del JWT actúa como referencia débil al usuario en `evaluado_por_id`.

---

### 3.4 Bounded Context: Notificaciones (`notifications`)

**Responsabilidad:** Comunicar de forma asíncrona el resultado de una evaluación al solicitante vía email. Implementado como **microservicio independiente `ms-notifications`** (`localhost:8083`). Consume eventos SQS publicados por `ms-credit-evaluation` sin ningún acoplamiento directo entre ambos.

#### Lenguaje Ubicuo

| Término | Definición |
|---------|-----------|
| `Notificacion` | Mensaje enviado por correo al solicitante |
| `Cola` | Canal SQS donde se publican los eventos de evaluación |
| `Destinatario` | Email del solicitante registrado con la cédula |
| `PlantillaEmail` | Contenido HTML/texto del correo según estado |
| `EstadoNotificacion` | PENDIENTE, ENVIADO, FALLIDO |

#### Entidades

```
Notificacion
├── id: UUID
├── evaluacionId: UUID (referencia por ID — cross-context)
├── destinatarioEmail: String
├── tipoNotificacion: TipoNotificacion (APROBADO | RECHAZADO)
├── estado: EstadoNotificacion
├── intentos: int
├── enviadoEn: Instant (nullable)
└── mensajeSqsId: String
```

---

## 4. Context Map

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                 CONTEXT MAP                                      │
│                                                                                  │
│  ┌────────────────────────┐  Customer/Supplier  ┌──────────────────────────┐    │
│  │  Evaluación de Crédito │ ──────────────────> │  Valoración de Riesgos   │    │
│  │  (Core)                │  REST sync          │  (Supporting)            │    │
│  │  ms-credit-evaluation  │ <────────────────── │  ms-risk :8081           │    │
│  │  :8080                 │                     └──────────────────────────┘    │
│  └───────────┬────────────┘                                                     │
│              │                                                                   │
│              │ Conformist / Open Host Service                                    │
│              │ (consume JWT emitido por Keycloak, valida via JWKS endpoint)      │
│              │ [no hay llamada runtime síncrona — claves cacheadas]              │
│              │                                                                   │
│  ┌───────────▼────────────┐                     ┌──────────────────────────┐    │
│  │  Identidad y Acceso    │                     │  Notificaciones          │    │
│  │  (Generic)             │                     │  (Supporting)            │    │
│  │  Keycloak :9000        │                     │  ms-notifications :8083  │    │
│  │  [sistema externo]     │                     └──────────────────────────┘    │
│  └────────────────────────┘                                ▲                    │
│              ▲                                             │ Published Language  │
│              │ OIDC/PKCE (login)                          │ SQS Events (async)  │
│              │ Admin Console (gestión)                     │                     │
│              │                                             │                     │
│           [Frontend] ── REST+JWT ──> [ms-credit-evaluation evalúa y publica]    │
│                                                                                  │
│  Relaciones:                                                                     │
│  → Customer/Supplier: ms-credit-evaluation depende de ms-risk como proveedor    │
│  → Conformist: ms-credit-evaluation acepta el contrato JWT de Keycloak           │
│    (Open Host Service — OIDC estándar, no un contrato propietario)              │
│  → Published Language: ms-credit-evaluation publica EvaluacionCompletada        │
│    en SQS, consumida por ms-notifications                                        │
│  → Keycloak y ms-notifications son autónomos: no llaman a ms-credit-evaluation  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Resumen de Eventos de Dominio por Contexto

```
[Evaluación de Crédito — ms-credit-evaluation]
  EvaluacionSolicitada ──────────────────────────────────────────────>
  EvaluacionCompletada ──> publica en SQS (fire-and-forget)
  EvaluacionFallida    ──> log + respuesta de error al cliente

[Identidad y Acceso — Keycloak]   ← sistema externo (OIDC)
  SesionIniciada       ──> emite JWT RS256 (access_token + refresh_token)
  TokenRefrescado      ──> cliente renueva JWT antes de expiración (keycloak-js)
  SesionExpirada       ──> ms-credit-evaluation retorna 401 (validación JWKS cacheada)
  UsuarioGestionado    ──> Admin Console / Admin REST API (fuera del flujo de negocio)

[Notificaciones — ms-notifications]   ← servicio independiente
  NotificacionEnviada  ──> actualiza estado en notifications_db
  NotificacionFallida  ──> reintento / DLQ
```
