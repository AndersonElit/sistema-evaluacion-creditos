# ms-auth — Autenticación, Login y Gestión de Usuarios

## 1. Arquitectura del Servicio

La autenticación y gestión de identidad está implementada como un **microservicio independiente (ms-auth)** que corre en el puerto `8082`. Esta separación garantiza que ms-credit-evaluation (Orquestador) tenga una única responsabilidad: evaluar créditos.

**Principio de integración:** ms-auth emite JWT firmados con una clave privada RSA. ms-credit-evaluation y cualquier otro servicio validan esos tokens usando únicamente la clave pública RSA, **sin llamadas en runtime a ms-auth**.

## 2. Estrategia de Autenticación

Se usa **JWT stateless** con el estándar **MicroProfile JWT** implementado en Quarkus vía `quarkus-smallrye-jwt`. No se usa Keycloak ni ningún servidor de autorización externo para mantener la arquitectura simple.

### Justificación: JWT stateless vs. sesiones en BD

| Criterio | JWT Stateless | Sesiones en BD |
|----------|:---:|:---:|
| Escalabilidad horizontal | ✅ Sin sesión compartida | ❌ Requiere sesión centralizada |
| Revocación inmediata | ❌ Esperar expiración | ✅ Borrar sesión |
| Rendimiento | ✅ Sin I/O por validación | ❌ Query por cada request |
| Complejidad | ✅ Solo firma y verificación | ❌ Gestión de store |
| Stateless | ✅ Ideal para microservicios | ❌ Estado compartido |

**Decisión:** JWT stateless es la opción correcta para un sistema de microservicios. La revocación se maneja con tokens de corta duración (8h) y la posibilidad de agregar un blocklist en Redis en el futuro si se requiere logout inmediato.

---

## 3. Flujo de Autenticación

```
PASO 1 — Login (solo ms-auth):

┌──────────┐  POST /v1/auth/login   ┌────────────────────────────────┐
│ Frontend │ ──────────────────────>│  ms-auth  :8082                │
│          │  {email, password}     │  1. Busca user en auth_db      │
│          │ <──────────────────── │  2. bcrypt.verify(pwd)         │
│          │  {accessToken,         │  3. Genera JWT (RS256)         │
│          │   expiresIn, usuario}  │                                │
└──────────┘                        └────────────────────────────────┘

PASO 2 — Uso del JWT (ms-credit-evaluation, sin llamar a ms-auth):

┌──────────┐  GET /v1/credit-evaluations    ┌────────────────────────────────┐
│ Frontend │ ─────────────────────────────> │  ms-credit-evaluation  :8080   │
│          │  Authorization: Bearer <jwt>   │  1. Verifica firma con         │
│          │                                │     clave pública RSA          │
│          │ <───────────────────────────── │  2. Verifica expiración        │
│          │  200 OK + datos                │  3. Extrae rol (groups)        │
└──────────┘                                │  4. Verifica @RolesAllowed     │
                                            └────────────────────────────────┘
                                            (no hay llamada HTTP a ms-auth)
```

---

## 4. Estructura del JWT

```json
{
  "header": {
    "alg": "RS256",
    "typ": "JWT"
  },
  "payload": {
    "iss": "https://api.banco.com",
    "sub": "analyst@banco.com",
    "iat": 1746446400,
    "exp": 1746475200,
    "groups": ["ANALYST"],
    "upn": "analyst@banco.com",
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "nombreCompleto": "María Pérez"
  }
}
```

**Notas:**
- `groups` es el claim estándar de MicroProfile JWT para roles
- `upn` (User Principal Name) es requerido por MicroProfile JWT
- `sub` = email del usuario para identificación única
- Firmado con **RS256** (clave privada en el servidor, pública disponible para verificación)

---

## 5. Configuración Quarkus

### Dependencias (`pom.xml`)

```xml
<!-- JWT Generation + Validation -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-jwt</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-jwt-build</artifactId>
</dependency>

<!-- Password hashing -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-elytron-security-common</artifactId>
</dependency>
```

### `application.properties` — ms-auth (Auth)

```properties
quarkus.http.port=8082

# ── JWT Generation (SmallRye JWT Build) ─────────────────────
smallrye.jwt.sign.key.location=META-INF/resources/privateKey.pem
mp.jwt.token.expiration.time=28800

# ── JWT Validation (para los endpoints protegidos de ms-auth) ───
mp.jwt.verify.publickey.location=META-INF/resources/publicKey.pem
mp.jwt.verify.issuer=https://auth.banco.com

# ── Password hashing ─────────────────────────────────────────
quarkus.security.users.embedded.enabled=false

# ── Datasource ───────────────────────────────────────────────
quarkus.datasource.jdbc.url=jdbc:postgresql://${AUTH_DB_HOST:localhost}:5433/auth_db
```

### `application.properties` — ms-credit-evaluation (validación JWT sin generación)

```properties
quarkus.http.port=8080

# ── JWT Validation únicamente — ms-credit-evaluation no genera tokens ────────
mp.jwt.verify.publickey.location=META-INF/resources/publicKey.pem
mp.jwt.verify.issuer=https://auth.banco.com

# La clave pública es la misma que usa ms-auth para firmar.
# Se distribuye como archivo PEM copiado en el build de ms-credit-evaluation,
# o descargada de GET http://auth-service:8082/v1/auth/public-key en startup.
# ms-credit-evaluation NO necesita la clave privada.
```

### Generación del par de claves RSA

```bash
# Generar clave privada (2048 bits)
openssl genrsa -out privateKey.pem 2048

# Extraer clave pública
openssl rsa -in privateKey.pem -pubout -out publicKey.pem

# Colocar en: src/main/resources/META-INF/resources/
```

---

## 6. Implementación de Endpoints (en ms-auth)

### `AuthResource.java`

```java
@Path("/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    @Inject AuthService authService;

    @POST
    @Path("/login")
    @PermitAll
    public Response login(@Valid LoginRequest request) {
        LoginResponse response = authService.login(request);
        return Response.ok(response).build();
    }

    @POST
    @Path("/users")
    @RolesAllowed("ADMIN")
    public Response crearUsuario(@Valid CrearUsuarioRequest request) {
        UsuarioResponse usuario = authService.crearUsuario(request);
        return Response.status(Response.Status.CREATED).entity(usuario).build();
    }

    @GET
    @Path("/users")
    @RolesAllowed("ADMIN")
    public List<UsuarioResponse> listarUsuarios() {
        return authService.listarUsuarios();
    }

    @GET
    @Path("/users/{userId}")
    @RolesAllowed("ADMIN")
    public UsuarioResponse obtenerUsuario(@PathParam("userId") UUID userId) {
        return authService.obtenerPorId(userId);
    }

    @PUT
    @Path("/users/{userId}/roles")
    @RolesAllowed("ADMIN")
    public UsuarioResponse actualizarRol(
            @PathParam("userId") UUID userId,
            @Valid ActualizarRolRequest request) {
        return authService.actualizarRol(userId, request.getRol());
    }
}
```

### `AuthService.java`

```java
@ApplicationScoped
public class AuthService {

    @Inject UserRepository userRepository;
    @Inject BcryptPasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
            .orElseThrow(() -> new UnauthorizedException("Credenciales inválidas"));

        if (!passwordEncoder.verify(request.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("Credenciales inválidas");
        }

        if (!user.isActivo()) {
            throw new UnauthorizedException("Usuario desactivado");
        }

        String token = Jwt.issuer("https://api.banco.com")
            .subject(user.getEmail())
            .upn(user.getEmail())
            .groups(user.getRol().getNombre())
            .claim("userId", user.getId().toString())
            .claim("nombreCompleto", user.getNombreCompleto())
            .expiresIn(Duration.ofHours(8))
            .sign();

        return new LoginResponse(token, "Bearer", 28800, UsuarioMapper.toResponse(user));
    }

    @Transactional
    public UsuarioResponse crearUsuario(CrearUsuarioRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("Email ya registrado");
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNombreCompleto(request.getNombreCompleto());
        user.setRol(Rol.valueOf(request.getRol()));
        user.setActivo(true);
        userRepository.persist(user);

        return UsuarioMapper.toResponse(user);
    }
}
```

---

## 7. Protección de Endpoints por Rol

Los endpoints de ms-credit-evaluation aplican `@RolesAllowed` sobre el JWT emitido por ms-auth:

```java
// En ms-credit-evaluation — Solo ADMIN y ANALYST pueden evaluar
@POST
@Path("/v1/credit-evaluations")
@RolesAllowed({"ADMIN", "ANALYST"})
public Response evaluarCredito(...) { ... }

// En ms-credit-evaluation — Todos los roles autenticados pueden ver la lista
@GET
@Path("/v1/credit-evaluations")
@Authenticated
public List<EvaluacionResponse> listar(...) { ... }

// En ms-auth — Solo ADMIN puede gestionar usuarios
@POST
@Path("/v1/auth/users")
@RolesAllowed("ADMIN")
public Response crearUsuario(...) { ... }
```

---

## 8. Gestión de Roles

### Roles del Sistema

| Rol | Código | Descripción |
|-----|--------|-------------|
| Administrador | `ADMIN` | Control total: gestiona usuarios, crea evaluaciones, ve todo |
| Analista | `ANALYST` | Crea y consulta evaluaciones crediticias |
| Observador | `VIEWER` | Solo lectura de evaluaciones ya realizadas |

### Reglas de Negocio de Roles

1. Solo `ADMIN` puede crear usuarios nuevos
2. Solo `ADMIN` puede cambiar el rol de otro usuario
3. Un usuario no puede cambiar su propio rol
4. No se puede eliminar físicamente un usuario, solo desactivar (soft delete)
5. Un `ADMIN` no puede degradarse a sí mismo si es el único `ADMIN`

---

## 9. Seguridad Adicional

### CORS Seguro

Cada microservicio configura su propio CORS. ms-auth acepta llamadas del frontend para login:

```properties
# application.properties de ms-auth
quarkus.http.cors=true
quarkus.http.cors.origins=http://localhost:3000,https://creditos.banco.com
quarkus.http.cors.methods=GET,POST,PUT,OPTIONS
quarkus.http.cors.headers=Content-Type,Authorization
quarkus.http.cors.exposed-headers=Location
quarkus.http.cors.access-control-max-age=24H
```

### Rate Limiting (protección contra brute force en login)

```java
// Usando Quarkus Cache para contar intentos fallidos
@ApplicationScoped
public class LoginAttemptService {
    private final Map<String, AtomicInteger> failedAttempts = new ConcurrentHashMap<>();
    private static final int MAX_ATTEMPTS = 5;

    public void registrarFallo(String email) {
        failedAttempts.computeIfAbsent(email, k -> new AtomicInteger(0))
                      .incrementAndGet();
    }

    public boolean estaBloqueado(String email) {
        return failedAttempts.getOrDefault(email, new AtomicInteger(0))
                             .get() >= MAX_ATTEMPTS;
    }
}
```

### Validación de Contraseña

```java
// Política: mínimo 8 chars, 1 mayúscula, 1 número, 1 símbolo
@Pattern(
    regexp = "^(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$",
    message = "La contraseña debe tener mínimo 8 caracteres, 1 mayúscula, 1 número y 1 símbolo"
)
private String password;
```

---

## 10. Flujo de Creación de Usuario (Admin)

```
[Admin UI]  →  POST /v1/auth/users  →  [ms-auth :8082]
                                              │
                                       ¿Email existe? (auth_db)
                                       ├── Sí → 409 Conflict
                                       └── No
                                              │
                                       Encode password (bcrypt 12)
                                              │
                                       Persist user + role en auth_db
                                              │
                                       Return 201 Created
                                              │
                                       [Admin UI muestra nuevo usuario]
```
