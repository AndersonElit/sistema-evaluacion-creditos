# Autenticación, Login y Gestión de Usuarios

## 1. Estrategia de Autenticación

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

## 2. Flujo de Autenticación

```
┌──────────┐    POST /v1/auth/login      ┌──────────────────────────┐
│ Frontend │ ──────────────────────────> │                          │
│          │   {email, password}         │   Microservicio A        │
│          │                             │   (Orquestador)          │
│          │ <────────────────────────── │                          │
│          │   {accessToken, expiresIn}  │   1. Busca user por email│
│          │                             │   2. bcrypt.verify(pwd)  │
│          │                             │   3. Genera JWT (RS256)  │
└──────────┘                             └──────────────────────────┘

Cada request subsiguiente:

┌──────────┐    GET /v1/credit-evaluations  ┌─────────────────────────┐
│ Frontend │ ────────────────────────────>  │  Microservicio A        │
│          │   Authorization: Bearer <jwt>  │                         │
│          │                                │  1. Valida firma JWT    │
│          │                                │  2. Verifica expiración │
│          │ <──────────────────────────── │  3. Extrae rol (groups) │
│          │   200 OK + datos               │  4. Verifica @RolesAllowed│
└──────────┘                                └─────────────────────────┘
```

---

## 3. Estructura del JWT

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

## 4. Configuración Quarkus

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

### `application.properties`

```properties
# ── JWT Validation ───────────────────────────────────────────
mp.jwt.verify.publickey.location=META-INF/resources/publicKey.pem
mp.jwt.verify.issuer=https://api.banco.com

# ── JWT Generation (SmallRye JWT Build) ─────────────────────
smallrye.jwt.sign.key.location=META-INF/resources/privateKey.pem
mp.jwt.token.expiration.time=28800

# ── Password hashing ─────────────────────────────────────────
quarkus.security.users.embedded.enabled=false
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

## 5. Implementación de Endpoints

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

## 6. Protección de Endpoints por Rol

```java
// Solo ADMIN y ANALYST pueden evaluar
@POST
@Path("/v1/credit-evaluations")
@RolesAllowed({"ADMIN", "ANALYST"})
public Response evaluarCredito(...) { ... }

// Todos los roles autenticados pueden ver la lista
@GET
@Path("/v1/credit-evaluations")
@Authenticated
public List<EvaluacionResponse> listar(...) { ... }

// Solo ADMIN puede gestionar usuarios
@POST
@Path("/v1/auth/users")
@RolesAllowed("ADMIN")
public Response crearUsuario(...) { ... }
```

---

## 7. Gestión de Roles

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

## 8. Seguridad Adicional

### CORS Seguro

```properties
# application.properties
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

## 9. Flujo de Creación de Usuario (Admin)

```
[Admin UI]  →  POST /v1/auth/users  →  [MS-A]
                                           │
                                    ¿Email existe?
                                    ├── Sí → 409 Conflict
                                    └── No
                                           │
                                    Encode password (bcrypt 12)
                                           │
                                    Persist user + role en BD
                                           │
                                    Return 201 Created
                                           │
                                    [Admin UI muestra nuevo usuario]
```
