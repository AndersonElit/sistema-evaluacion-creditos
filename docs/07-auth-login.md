# Keycloak — Autenticación, Login y Gestión de Usuarios

## 1. Arquitectura del Servicio

La autenticación y gestión de identidad está delegada a **Keycloak 24.x**, un OIDC Provider de grado enterprise. Keycloak corre como contenedor independiente en el puerto `9000` (host) bajo el realm `banco`.

**Principio de integración:** Keycloak emite JWT firmados con RS256 y expone un JWKS endpoint. ms-credit-evaluation valida los tokens consultando ese endpoint — sin ningún código propio de autenticación.

---

## 2. Flujo de Autenticación (Authorization Code + PKCE)

```
PASO 1 — Login (Frontend redirige a Keycloak):

┌──────────┐  GET /realms/banco/protocol/openid-connect/auth   ┌─────────────────────┐
│ Frontend │ ────────────────────────────────────────────────> │  Keycloak  :9000     │
│ React    │  ?client_id=credit-evaluation-spa                 │  Realm: banco        │
│          │  &response_type=code                              │                     │
│          │  &redirect_uri=http://localhost:3000/callback     │  Muestra pantalla   │
│          │  &scope=openid profile email                      │  de login propia     │
│          │  &code_challenge=<PKCE_S256>                      └──────────┬──────────┘
└──────────┘                                                              │
                                                                          │ redirect con code
                                                                          ▼
PASO 2 — Intercambio de code por tokens:

┌──────────┐  POST /realms/banco/protocol/openid-connect/token  ┌─────────────────────┐
│ Frontend │ ────────────────────────────────────────────────> │  Keycloak  :9000     │
│          │  grant_type=authorization_code                     │  Valida code + PKCE  │
│          │  code=<code>                                       │  Genera JWT RS256    │
│          │  code_verifier=<PKCE_verifier>                     └──────────┬──────────┘
│          │ <────────────────────────────────────────────────             │
│          │  { access_token, refresh_token, id_token }                    │
└──────────┘

PASO 3 — Uso del JWT (ms-credit-evaluation valida contra JWKS de Keycloak):

┌──────────┐  GET /v1/credit-evaluations                   ┌─────────────────────────────┐
│ Frontend │ ─────────────────────────────────────────────>│  ms-credit-evaluation :8080  │
│          │  Authorization: Bearer <access_token>         │  1. Descarga JWKS de         │
│          │                                               │     Keycloak (cacheado)      │
│          │ <──────────────────────────────────────────── │  2. Verifica firma RS256      │
│          │  200 OK + datos                               │  3. Verifica expiración       │
└──────────┘                                               │  4. Extrae rol (groups claim) │
                                                           │  5. Verifica @RolesAllowed    │
                                                           └─────────────────────────────┘
                                                           (no hay llamada HTTP a Keycloak
                                                            por cada request — cache JWKS)
```

---

## 3. Estructura del JWT emitido por Keycloak

```json
{
  "header": {
    "alg": "RS256",
    "typ": "JWT",
    "kid": "abc123..."
  },
  "payload": {
    "iss": "http://localhost:9000/realms/banco",
    "sub": "550e8400-e29b-41d4-a716-446655440000",
    "aud": "credit-evaluation-spa",
    "exp": 1746446700,
    "iat": 1746446400,
    "jti": "unique-token-id",
    "email": "analyst@banco.com",
    "name": "María Pérez",
    "preferred_username": "analyst@banco.com",
    "realm_access": {
      "roles": ["ANALYST", "offline_access", "uma_authorization"]
    },
    "groups": ["ANALYST"]
  }
}
```

**Notas:**
- `groups` se agrega vía un **Protocol Mapper** configurado en el Client de Keycloak para compatibilidad con `@RolesAllowed` de SmallRye JWT
- `sub` es el UUID del usuario en Keycloak — se usa como `evaluado_por_id` (referencia débil) en `credit_evaluations`
- `iss` debe coincidir con `mp.jwt.verify.issuer` en ms-credit-evaluation
- Access token por defecto: **5 minutos** (configurable en Realm Settings). Keycloak JS renueva automáticamente con el refresh token

---

## 4. Configuración Keycloak

### Docker Compose (desarrollo local)

```yaml
services:
  keycloak:
    image: quay.io/keycloak/keycloak:24.0
    command: start-dev
    ports:
      - "9000:8080"
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres-keycloak:5432/keycloak_db
      KC_DB_USERNAME: postgres
      KC_DB_PASSWORD: postgres
    depends_on:
      - postgres-keycloak

  postgres-keycloak:
    image: postgres:16
    ports:
      - "5435:5432"
    environment:
      POSTGRES_DB: keycloak_db
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
```

> Para simplificar el entorno local se puede usar `KC_DB=dev-file` (H2 embebido) eliminando el contenedor `postgres-keycloak`. No usar en producción.

### Configuración del Realm `banco`

```bash
# Importar realm via Admin CLI o exportar/importar JSON
# Realm: banco
# Token lifespan: 300s (access) / 1800s (refresh)
# SSL: none (dev) / required (prod)
```

### Configuración del Client `credit-evaluation-spa`

```
Client ID:        credit-evaluation-spa
Client Protocol:  openid-connect
Access Type:      public  (sin client secret — PKCE obligatorio)
Valid Redirect URIs:
  http://localhost:3000/*
  https://creditos.banco.com/*
Web Origins:
  http://localhost:3000
  https://creditos.banco.com
```

### Protocol Mapper — roles → groups claim

```
Mapper Type:       User Realm Role
Token Claim Name:  groups
Claim JSON Type:   String
Add to ID token:   ON
Add to access token: ON
```

---

## 5. Configuración Quarkus — ms-credit-evaluation

### `application.properties`

```properties
quarkus.http.port=8080

# ── JWT Validation via Keycloak JWKS ─────────────────────────
mp.jwt.verify.publickey.location=http://keycloak:9000/realms/banco/protocol/openid-connect/certs
mp.jwt.verify.issuer=http://localhost:9000/realms/banco

# En producción:
# mp.jwt.verify.publickey.location=https://auth.banco.com/realms/banco/protocol/openid-connect/certs
# mp.jwt.verify.issuer=https://auth.banco.com/realms/banco

# ── Roles — leer del claim "groups" (mapeado desde realm_access.roles)
quarkus.smallrye-jwt.role-paths=groups

# ── CORS ─────────────────────────────────────────────────────
quarkus.http.cors=true
quarkus.http.cors.origins=http://localhost:3000,https://creditos.banco.com
quarkus.http.cors.methods=GET,POST,OPTIONS
quarkus.http.cors.headers=Content-Type,Authorization
```

### Dependencias (`pom.xml`)

```xml
<!-- JWT Validation únicamente — no se genera tokens -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-jwt</artifactId>
</dependency>
```

### Protección de Endpoints por Rol

```java
// Solo ADMIN y ANALYST pueden crear evaluaciones
@POST
@Path("/v1/credit-evaluations")
@RolesAllowed({"ADMIN", "ANALYST"})
public Response evaluarCredito(@Context SecurityContext ctx, ...) {
    String evaluadorId = jwt.getSubject(); // sub claim = UUID del usuario en Keycloak
    ...
}

// Todos los roles autenticados pueden ver la lista
@GET
@Path("/v1/credit-evaluations")
@Authenticated
public List<EvaluacionResponse> listar(...) { ... }
```

---

## 6. Configuración Frontend — keycloak-js

### Instalación

```bash
npm install keycloak-js
```

### `keycloak.ts`

```typescript
import Keycloak from 'keycloak-js';

const keycloak = new Keycloak({
  url: 'http://localhost:9000',
  realm: 'banco',
  clientId: 'credit-evaluation-spa',
});

export default keycloak;
```

### Inicialización en `main.tsx`

```typescript
import keycloak from './keycloak';

keycloak.init({
  onLoad: 'login-required',
  pkceMethod: 'S256',
  checkLoginIframe: false,
}).then((authenticated) => {
  if (authenticated) {
    ReactDOM.createRoot(document.getElementById('root')!).render(
      <App keycloak={keycloak} />
    );
  }
});

// Renovación automática del token (30s antes de expirar)
keycloak.onTokenExpired = () => {
  keycloak.updateToken(30).catch(() => keycloak.logout());
};
```

### Axios interceptor — adjuntar Bearer token

```typescript
axiosInstance.interceptors.request.use(async (config) => {
  await keycloak.updateToken(30); // renueva si queda < 30s
  config.headers['Authorization'] = `Bearer ${keycloak.token}`;
  return config;
});
```

---

## 7. Gestión de Usuarios y Roles

### Roles del Sistema

| Rol | Código | Descripción |
|-----|--------|-------------|
| Administrador | `ADMIN` | Control total: gestiona evaluaciones, ve todo. Gestiona usuarios vía Keycloak Admin Console |
| Analista | `ANALYST` | Crea y consulta evaluaciones crediticias |
| Observador | `VIEWER` | Solo lectura de evaluaciones ya realizadas |

### Gestión vía Keycloak Admin Console

La gestión de usuarios se realiza directamente en Keycloak (`http://localhost:9000/admin`), no mediante endpoints del sistema de créditos:

| Operación | Keycloak Admin Console | Keycloak Admin REST API |
|-----------|----------------------|------------------------|
| Crear usuario | Users → Add User | `POST /admin/realms/banco/users` |
| Asignar rol | Users → Role Mappings | `POST /admin/realms/banco/users/{id}/role-mappings/realm` |
| Desactivar usuario | Users → Edit → Enabled = OFF | `PUT /admin/realms/banco/users/{id}` |
| Resetear contraseña | Users → Credentials → Reset | `PUT /admin/realms/banco/users/{id}/reset-password` |

### Usuario Admin Inicial

```bash
# Crear primer usuario admin vía Keycloak Admin CLI
/opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:9000 \
  --realm master \
  --user admin \
  --password admin

# Crear realm
/opt/keycloak/bin/kcadm.sh create realms \
  -s realm=banco \
  -s enabled=true

# Crear usuario analista
/opt/keycloak/bin/kcadm.sh create users \
  -r banco \
  -s username=analyst@banco.com \
  -s email=analyst@banco.com \
  -s enabled=true

# Asignar rol
/opt/keycloak/bin/kcadm.sh add-roles \
  -r banco \
  --uusername analyst@banco.com \
  --rolename ANALYST
```

---

## 8. Seguridad Adicional

### Revocación de Tokens

A diferencia de JWT stateless, Keycloak permite **revocación inmediata**:
- Logout activo: `POST /realms/banco/protocol/openid-connect/logout` (invalida refresh token)
- Revocar todas las sesiones de un usuario: Admin Console → Users → Sessions → Logout All

### Política de Contraseñas (Realm Settings)

Configurable en Keycloak Admin Console → Authentication → Password Policy:
- Longitud mínima: 8 caracteres
- Al menos 1 mayúscula, 1 dígito, 1 símbolo especial
- Historial: últimas 3 contraseñas no reutilizables

### Brute Force Protection

Keycloak protege contra brute force de forma nativa:
- Admin Console → Realm Settings → Security Defenses → Brute Force Detection
- Bloqueo temporal tras N intentos fallidos (configurable)
