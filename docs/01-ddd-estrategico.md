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
| `EvaluacionFallida` | Error al contactar servicio de riesgos | Timeout o error 5xx de Microservicio B |

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

**Responsabilidad:** Gestionar usuarios, credenciales, roles y emisión de tokens JWT.

#### Lenguaje Ubicuo

| Término | Definición |
|---------|-----------|
| `Usuario` | Persona registrada en el sistema con credenciales válidas |
| `Credenciales` | Email y contraseña bcrypt del usuario |
| `Token` | JWT firmado que prueba la identidad y los permisos |
| `Rol` | Conjunto de permisos asignado a un usuario (ADMIN, ANALYST, VIEWER) |
| `Sesion` | Período de validez del token (stateless, 8 horas) |

#### Roles y Permisos

| Rol | Puede evaluar | Puede ver lista | Puede crear usuarios | Puede asignar roles |
|-----|:---:|:---:|:---:|:---:|
| `ADMIN` | ✅ | ✅ | ✅ | ✅ |
| `ANALYST` | ✅ | ✅ | ❌ | ❌ |
| `VIEWER` | ❌ | ✅ | ❌ | ❌ |

#### Agregados

```
Aggregate Root: Usuario
├── id: UUID
├── email: Email (Value Object)
├── passwordHash: String (bcrypt)
├── nombreCompleto: String
├── activo: boolean
├── creadoEn: Instant
└── roles: Set<Rol>

Rol
├── id: UUID
├── nombre: NombreRol (Enum: ADMIN, ANALYST, VIEWER)
└── descripcion: String
```

---

### 3.4 Bounded Context: Notificaciones (`notifications`)

**Responsabilidad:** Comunicar de forma asíncrona el resultado de una evaluación al solicitante vía email.

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
┌─────────────────────────────────────────────────────────────────────┐
│                        CONTEXT MAP                                   │
│                                                                      │
│  ┌──────────────────┐   Customer/Supplier   ┌─────────────────────┐ │
│  │  Evaluación de   │ ─────────────────────>│  Valoración de      │ │
│  │  Crédito (Core)  │   REST (sync)          │  Riesgos (Support)  │ │
│  │                  │ <─────────────────────│                     │ │
│  └────────┬─────────┘                       └─────────────────────┘ │
│           │                                                          │
│           │ Conformist                                               │
│           │ (consume JWT sin modificarlo)                            │
│           │                                                          │
│  ┌────────▼─────────┐   Published Language  ┌─────────────────────┐ │
│  │  Identidad y     │ ─────────────────────>│  Notificaciones     │ │
│  │  Acceso (Generic)│   SQS Events (async)  │  (Supporting)       │ │
│  └──────────────────┘                       └─────────────────────┘ │
│                                                                      │
│  Relaciones:                                                         │
│  → Customer/Supplier: Evaluación depende de Riesgos como proveedor  │
│  → Conformist: Evaluación acepta el contrato JWT de Identidad       │
│  → Published Language: Evaluación publica eventos que consume       │
│    Notificaciones a través de SQS                                   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 5. Resumen de Eventos de Dominio por Contexto

```
[Evaluación de Crédito]
  EvaluacionSolicitada ──────────────────────────────────────────────>
  EvaluacionCompletada ──> [Notificaciones] : publica en SQS
  EvaluacionFallida    ──> log + respuesta de error al cliente

[Identidad y Acceso]
  UsuarioCreado        ──> log interno
  SesionIniciada       ──> emite JWT
  SesionExpirada       ──> cliente recibe 401

[Notificaciones]
  NotificacionEnviada  ──> actualiza estado en BD
  NotificacionFallida  ──> reintento / DLQ
```
