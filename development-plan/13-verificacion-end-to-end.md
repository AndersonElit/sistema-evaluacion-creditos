# Paso 12 — Verificación End-to-End: Happy Path y Escenarios de Seguridad

## Objetivo
Ejecutar el flujo completo del sistema de extremo a extremo, validar los
escenarios BDD críticos y los controles de seguridad definidos en `10-sdd.md`.
Todos los comandos son ejecutables localmente con curl y psql.

## Prerrequisitos
- Todos los pasos anteriores completados
- Sistema corriendo (modo dev o Docker Compose)
- Variables de entorno configuradas

```bash
# Exportar URLs base para los comandos
export KC_URL="http://localhost:9000"
export API_URL="http://localhost:8080"
export RISK_URL="http://localhost:8081"
export NOTIF_URL="http://localhost:8083"
export SQS_URL="http://localhost:4566/000000000000/credit-evaluation-notifications"
```

---

## 1. Obtener Tokens de Prueba

```bash
# Token ANALYST
ANALYST_TOKEN=$(curl -s -X POST \
  "$KC_URL/realms/banco/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=credit-evaluation-spa" \
  -d "username=analyst@banco.com&password=Analyst123!" \
  | jq -r '.access_token')

# Token VIEWER
VIEWER_TOKEN=$(curl -s -X POST \
  "$KC_URL/realms/banco/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=credit-evaluation-spa" \
  -d "username=viewer@banco.com&password=Viewer123!" \
  | jq -r '.access_token')

# Verificar contenido del JWT (debe tener groups: ["ANALYST"])
echo $ANALYST_TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | jq '{email, groups, exp}'
```

---

## 2. Happy Path — Evaluación Aprobada

```bash
# Crear evaluación (cédula válida: 1713175071)
RESP=$(curl -s -X POST "$API_URL/v1/credit-evaluations" \
  -H "Authorization: Bearer $ANALYST_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "cedula": "1713175071",
    "montoSolicitado": 5000.00,
    "plazoAnios": 3,
    "salario": 2000.00,
    "destinatarioEmail": "solicitante@email.com"
  }')

echo $RESP | jq .
EVAL_ID=$(echo $RESP | jq -r '.id')
echo "Evaluacion ID: $EVAL_ID"
echo "Estado: $(echo $RESP | jq -r '.estadoFinal')"
# APROBADO o RECHAZADO (score aleatorio de ms-risk)

# Verificar persistencia en BD
psql -h localhost -p 5432 -U postgres -d creditos_db \
  -c "SELECT cedula, monto_solicitado, score_riesgo, estado_final FROM credit_evaluations ORDER BY fecha_evaluacion DESC LIMIT 1;"

# Esperar ~50s y verificar notificación en ms-notifications
psql -h localhost -p 5434 -U postgres -d notifications_db \
  -c "SELECT evaluacion_id, destinatario_email, estado, enviado_en FROM notifications ORDER BY creado_en DESC LIMIT 1;"
# estado: ENVIADO

# Verificar email en LocalStack
curl -s http://localhost:4566/_localstack/ses/ | jq '.[0].Destination'
```

---

## 3. Escenarios de Validación de Entrada (BDD 02)

```bash
# Cédula inválida (no pasa Módulo 10) → 422
curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$API_URL/v1/credit-evaluations" \
  -H "Authorization: Bearer $ANALYST_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cedula":"1234567890","montoSolicitado":5000,"plazoAnios":3,"salario":2000}'
# 422

# Monto negativo → 422
curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$API_URL/v1/credit-evaluations" \
  -H "Authorization: Bearer $ANALYST_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cedula":"1713175071","montoSolicitado":-500,"plazoAnios":3,"salario":2000}'
# 422

# Salario 0 → 422
curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$API_URL/v1/credit-evaluations" \
  -H "Authorization: Bearer $ANALYST_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cedula":"1713175071","montoSolicitado":5000,"plazoAnios":3,"salario":0}'
# 422

# Plazo 0 → 422
curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$API_URL/v1/credit-evaluations" \
  -H "Authorization: Bearer $ANALYST_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cedula":"1713175071","montoSolicitado":5000,"plazoAnios":0,"salario":2000}'
# 422

# JSON malformado → 400
curl -s -X POST "$API_URL/v1/credit-evaluations" \
  -H "Authorization: Bearer $ANALYST_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ invalid json %%%' | jq .
# {"status":400,...} sin stack trace Java
```

---

## 4. Controles de Acceso (BDD seguridad — STRIDE Spoofing/EoP)

```bash
# Sin token → 401
curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$API_URL/v1/credit-evaluations" \
  -H "Content-Type: application/json" \
  -d '{}'
# 401

# Sin token en GET → 401
curl -s -o /dev/null -w "%{http_code}" "$API_URL/v1/credit-evaluations"
# 401

# VIEWER intenta crear evaluación → 403
curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$API_URL/v1/credit-evaluations" \
  -H "Authorization: Bearer $VIEWER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cedula":"1713175071","montoSolicitado":5000,"plazoAnios":3,"salario":2000}'
# 403

# Token con firma manipulada → 401
TAMPERED=$(echo $ANALYST_TOKEN | sed 's/\(.*\)\.\(.*\)\.\(.*\)/\1.TAMPERED.\3/')
curl -s -o /dev/null -w "%{http_code}" \
  -X GET "$API_URL/v1/credit-evaluations" \
  -H "Authorization: Bearer $TAMPERED"
# 401

# VIEWER puede hacer GET → 200
curl -s -o /dev/null -w "%{http_code}" \
  -X GET "$API_URL/v1/credit-evaluations" \
  -H "Authorization: Bearer $VIEWER_TOKEN"
# 200
```

---

## 5. Inyección SQL (BDD seguridad — STRIDE Tampering)

```bash
# Intentos de inyección SQL en cédula — todos deben retornar 422
for PAYLOAD in \
  "1' OR '1'='1" \
  "'; DROP TABLE credit_evaluations; --" \
  "1713175071' UNION SELECT 1--" \
  '<script>alert(1)</script>' \
  '${7*7}'; do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$API_URL/v1/credit-evaluations" \
    -H "Authorization: Bearer $ANALYST_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"cedula\":\"${PAYLOAD}\",\"montoSolicitado\":5000,\"plazoAnios\":3,\"salario\":2000}")
  echo "Payload: $PAYLOAD → HTTP $CODE"
  # Esperado: 422 para todos
done

# Verificar que la BD no fue afectada
psql -h localhost -p 5432 -U postgres -d creditos_db \
  -c "\dt" | grep credit_evaluations
# La tabla debe seguir existiendo
```

---

## 6. Consulta por ID

```bash
# Buscar la evaluación creada en el paso 2
curl -s "$API_URL/v1/credit-evaluations/$EVAL_ID" \
  -H "Authorization: Bearer $ANALYST_TOKEN" | jq .
# 200 con los datos de la evaluación

# ID inexistente → 404
curl -s -o /dev/null -w "%{http_code}" \
  "$API_URL/v1/credit-evaluations/00000000-0000-0000-0000-000000000000" \
  -H "Authorization: Bearer $ANALYST_TOKEN"
# 404
```

---

## 7. Resiliencia — ms-risk caído

```bash
# Detener ms-risk
docker stop ms-risk  # o matar el proceso en modo dev

# Intentar evaluación → 503
curl -s -X POST "$API_URL/v1/credit-evaluations" \
  -H "Authorization: Bearer $ANALYST_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cedula":"1713175071","montoSolicitado":5000,"plazoAnios":3,"salario":2000}' | jq .
# {"status":503,"error":"El servicio de riesgos no está disponible..."}

# Verificar que no se persistió ninguna evaluación fallida
psql -h localhost -p 5432 -U postgres -d creditos_db \
  -c "SELECT COUNT(*) FROM credit_evaluations;"
# El conteo no debe haber aumentado

# Reiniciar ms-risk
docker start ms-risk  # o volver a arrancar mvn quarkus:dev
```

---

## 8. Idempotencia de Notificaciones

```bash
# Publicar el mismo mensaje dos veces en SQS
for i in 1 2; do
  aws --endpoint-url=http://localhost:4566 sqs send-message \
    --region us-east-1 --no-sign-request \
    --queue-url "$SQS_URL" \
    --message-body '{
      "evaluacionId": "11111111-2222-3333-4444-555555555555",
      "cedula": "1713175071",
      "destinatarioEmail": "idem@email.com",
      "estadoFinal": "APROBADO",
      "montoSolicitado": 5000.00,
      "moneda": "USD",
      "plazoAnios": 3,
      "fechaEvaluacion": "2026-05-05T14:30:00Z",
      "version": "1.0"
    }'
  echo "Enviado mensaje $i"
done

# Esperar ~60s y verificar
psql -h localhost -p 5434 -U postgres -d notifications_db \
  -c "SELECT COUNT(*) FROM notifications WHERE evaluacion_id = '11111111-2222-3333-4444-555555555555';"
# Debe ser exactamente 1 (no 2)
```

---

## 9. Health Checks Finales

```bash
echo "=== ms-credit-evaluation ==="
curl -s http://localhost:8080/q/health | jq '{status, checks: [.checks[].name]}'

echo "=== ms-risk ==="
curl -s http://localhost:8081/q/health | jq .status

echo "=== ms-notifications ==="
curl -s http://localhost:8083/q/health | jq .status

echo "=== Keycloak ==="
curl -s http://localhost:9000/health/ready

echo "=== Swagger UIs ==="
echo "ms-credit-evaluation: http://localhost:8080/swagger-ui"
echo "ms-risk:              http://localhost:8081/swagger-ui"
```

---

## Checklist Final del Sistema

### Funcionalidad
- [ ] Flujo completo: login → evaluación → persistencia → SQS → email
- [ ] Evaluación retorna `APROBADO` o `RECHAZADO` con score y deuda reales de ms-risk
- [ ] Llamadas a ms-risk son paralelas (~2s, no 3.5s)
- [ ] Notificación llega a `notifications_db` en estado `ENVIADO`
- [ ] Idempotencia: dos mensajes del mismo `evaluacionId` → 1 sola fila en BD

### Seguridad
- [ ] Sin token → 401 en todos los endpoints
- [ ] VIEWER en POST → 403
- [ ] Token manipulado → 401
- [ ] Cédula inválida (Módulo 10) → 422
- [ ] Inyección SQL en cédula → 422 (sin ejecución en BD)
- [ ] ms-risk caído → 503, no persiste evaluación

### Infraestructura
- [ ] Migraciones aplicadas manualmente en `creditos_db` y `notifications_db` (pasos 05 y 08)
- [ ] Circuit Breaker activo en llamadas a ms-risk
- [ ] DLQ disponible en LocalStack
- [ ] Keycloak JWKS endpoint responde (`/realms/banco/protocol/openid-connect/certs`)
