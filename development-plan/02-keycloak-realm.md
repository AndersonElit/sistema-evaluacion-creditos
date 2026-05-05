# Paso 02 — Keycloak: Realm, Client, Roles y Usuarios

## Objetivo
Configurar el realm `banco` en Keycloak con el client OIDC para el Frontend,
los roles del sistema (ADMIN, ANALYST, VIEWER), el protocol mapper que expone
los roles en el claim `groups`, y los usuarios de prueba necesarios para desarrollo.

## Prerrequisitos
- Paso 01 completado (Keycloak corriendo en http://localhost:9000)
- `kcadm.sh` disponible dentro del contenedor Keycloak o via Admin REST API

## Opción A — Configuración vía Admin CLI (dentro del contenedor)

```bash
# Acceder al contenedor
docker exec -it keycloak bash

# Autenticar contra el realm master
/opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 \
  --realm master \
  --user admin \
  --password admin
```

### 1. Crear el Realm `banco`
```bash
/opt/keycloak/bin/kcadm.sh create realms \
  -s realm=banco \
  -s enabled=true \
  -s displayName="Sistema de Evaluación de Créditos" \
  -s accessTokenLifespan=300 \
  -s refreshTokenMaxReuse=0
```

### 2. Activar Brute Force Protection
```bash
/opt/keycloak/bin/kcadm.sh update realms/banco \
  -s bruteForceProtected=true \
  -s failureFactor=5 \
  -s waitIncrementSeconds=60
```

### 3. Crear Roles del Realm
```bash
/opt/keycloak/bin/kcadm.sh create roles -r banco -s name=ADMIN   -s description="Control total"
/opt/keycloak/bin/kcadm.sh create roles -r banco -s name=ANALYST -s description="Crea y consulta evaluaciones"
/opt/keycloak/bin/kcadm.sh create roles -r banco -s name=VIEWER  -s description="Solo lectura"
```

### 4. Crear el Client `credit-evaluation-spa`
```bash
/opt/keycloak/bin/kcadm.sh create clients -r banco \
  -s clientId=credit-evaluation-spa \
  -s protocol=openid-connect \
  -s publicClient=true \
  -s standardFlowEnabled=true \
  -s directAccessGrantsEnabled=false \
  -s 'redirectUris=["http://localhost:3000/*"]' \
  -s 'webOrigins=["http://localhost:3000"]'
```

### 5. Protocol Mapper — roles → claim `groups`
```bash
# Obtener el ID del client
CLIENT_ID=$(/opt/keycloak/bin/kcadm.sh get clients -r banco -q clientId=credit-evaluation-spa \
  --fields id --format csv --noquotes)

/opt/keycloak/bin/kcadm.sh create clients/$CLIENT_ID/protocol-mappers/models -r banco \
  -s name=groups-mapper \
  -s protocol=openid-connect \
  -s protocolMapper=oidc-usermodel-realm-role-mapper \
  -s 'config."claim.name"=groups' \
  -s 'config."jsonType.label"=String' \
  -s 'config."multivalued"=true' \
  -s 'config."id.token.claim"=true' \
  -s 'config."access.token.claim"=true'
```

### 6. Crear Usuarios de Prueba
```bash
# Analista
/opt/keycloak/bin/kcadm.sh create users -r banco \
  -s username=analyst@banco.com \
  -s email=analyst@banco.com \
  -s firstName=Maria \
  -s lastName=Perez \
  -s enabled=true

/opt/keycloak/bin/kcadm.sh set-password -r banco \
  --username analyst@banco.com --new-password Analyst123!

/opt/keycloak/bin/kcadm.sh add-roles -r banco \
  --uusername analyst@banco.com --rolename ANALYST

# Administrador
/opt/keycloak/bin/kcadm.sh create users -r banco \
  -s username=admin@banco.com \
  -s email=admin@banco.com \
  -s firstName=Carlos \
  -s lastName=Gomez \
  -s enabled=true

/opt/keycloak/bin/kcadm.sh set-password -r banco \
  --username admin@banco.com --new-password Admin123!

/opt/keycloak/bin/kcadm.sh add-roles -r banco \
  --uusername admin@banco.com --rolename ADMIN

# Viewer (solo lectura)
/opt/keycloak/bin/kcadm.sh create users -r banco \
  -s username=viewer@banco.com \
  -s email=viewer@banco.com \
  -s firstName=Ana \
  -s lastName=Torres \
  -s enabled=true

/opt/keycloak/bin/kcadm.sh set-password -r banco \
  --username viewer@banco.com --new-password Viewer123!

/opt/keycloak/bin/kcadm.sh add-roles -r banco \
  --uusername viewer@banco.com --rolename VIEWER
```

## Opción B — Importar realm JSON

Guardar el archivo `scripts/keycloak-realm-banco.json` con la configuración
exportada y al iniciar Keycloak agregar `--import-realm` al comando.
Útil para reproducir el entorno automáticamente.

```bash
# Exportar realm después de configurar con CLI:
docker exec keycloak /opt/keycloak/bin/kc.sh export \
  --realm banco --file /tmp/realm-banco.json
docker cp keycloak:/tmp/realm-banco.json scripts/keycloak-realm-banco.json
```

## Verificación

```bash
# 1. Obtener token vía password grant (solo para testing — no usar en producción)
TOKEN=$(curl -s -X POST \
  "http://localhost:9000/realms/banco/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=credit-evaluation-spa" \
  -d "username=analyst@banco.com" \
  -d "password=Analyst123!" \
  | jq -r '.access_token')

echo $TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | jq .
# Debe mostrar:
# "iss": "http://localhost:9000/realms/banco"
# "groups": ["ANALYST"]
# "email": "analyst@banco.com"

# 2. Verificar JWKS endpoint
curl -s http://localhost:9000/realms/banco/protocol/openid-connect/certs | jq .

# 3. Verificar OIDC discovery
curl -s http://localhost:9000/realms/banco/.well-known/openid-configuration | jq .
```

## Estado esperado al finalizar
- [ ] Realm `banco` creado y activo
- [ ] Roles: ADMIN, ANALYST, VIEWER en el realm
- [ ] Client `credit-evaluation-spa` configurado como public + PKCE
- [ ] Protocol Mapper: claim `groups` con roles del realm
- [ ] Brute Force Protection activado
- [ ] Usuarios: analyst@banco.com (ANALYST), admin@banco.com (ADMIN), viewer@banco.com (VIEWER)
- [ ] JWT de prueba contiene `groups: ["ANALYST"]` y `iss: "http://localhost:9000/realms/banco"`
