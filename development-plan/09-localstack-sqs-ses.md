# Paso 09 — LocalStack: Verificación y Configuración SQS + SES

## Objetivo
Verificar que las colas SQS y la identidad SES estén correctamente creadas
en LocalStack, configurar el `init.sh` para que sea reproducible en cada
reinicio del contenedor, y validar el flujo de publicación/consumo de mensajes.

> Este paso puede ejecutarse en paralelo con el paso 08, pero las colas deben
> existir antes de arrancar `ms-notifications` para que el consumer funcione.

## Prerrequisitos
- Paso 01 completado (LocalStack corriendo en puerto 4566)
- `awslocal` o AWS CLI configurado con endpoint-override

## Alias recomendado (agregar a `~/.bashrc` o `~/.zshrc`)

```bash
alias awslocal='aws --endpoint-url=http://localhost:4566 --region us-east-1 --no-sign-request'
```

## 1. Verificar colas creadas por el init script

```bash
# Listar colas
awslocal sqs list-queues
# Esperado:
# http://localhost:4566/000000000000/credit-eval-notif-dlq
# http://localhost:4566/000000000000/credit-evaluation-notifications

# Atributos de la cola principal
awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/credit-evaluation-notifications \
  --attribute-names All | jq .
# VisibilityTimeout: 60
# ReceiveMessageWaitTimeSeconds: 20
# RedrivePolicy: maxReceiveCount=3
```

## 2. Si las colas no existen — crearlas manualmente

```bash
# DLQ primero
awslocal sqs create-queue --queue-name credit-eval-notif-dlq \
  --attributes MessageRetentionPeriod=1209600

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/credit-eval-notif-dlq \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

echo "DLQ ARN: $DLQ_ARN"

# Cola principal con redrive
awslocal sqs create-queue \
  --queue-name credit-evaluation-notifications \
  --attributes \
    VisibilityTimeout=60,\
    ReceiveMessageWaitTimeSeconds=20,\
    MessageRetentionPeriod=86400,\
    "RedrivePolicy={\"deadLetterTargetArn\":\"${DLQ_ARN}\",\"maxReceiveCount\":\"3\"}"
```

## 3. Verificar SES (identidad de email)

```bash
# Listar identidades verificadas
awslocal ses list-identities
# Esperado: noreply@banco.com

# Si no existe, verificarla
awslocal ses verify-email-identity --email-address noreply@banco.com

# Verificar status
awslocal ses get-identity-verification-attributes \
  --identities noreply@banco.com | jq .
```

## 4. Test de publicación manual en la cola

```bash
# Publicar mensaje de prueba
MSG_ID=$(awslocal sqs send-message \
  --queue-url http://localhost:4566/000000000000/credit-evaluation-notifications \
  --message-body '{
    "evaluacionId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
    "cedula": "1713175071",
    "destinatarioEmail": "prueba@email.com",
    "estadoFinal": "APROBADO",
    "montoSolicitado": 5000.00,
    "moneda": "USD",
    "plazoAnios": 3,
    "fechaEvaluacion": "2026-05-05T14:30:00Z",
    "version": "1.0"
  }' \
  --query 'MessageId' --output text)

echo "Message ID: $MSG_ID"

# Ver mensajes pendientes en la cola
awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/credit-evaluation-notifications \
  --attribute-names ApproximateNumberOfMessages | jq .
```

## 5. Test de envío de email vía SES (LocalStack)

```bash
# Enviar email de prueba directamente
awslocal ses send-email \
  --from noreply@banco.com \
  --destination '{"ToAddresses":["test@email.com"]}' \
  --message '{
    "Subject": {"Data": "Prueba LocalStack SES"},
    "Body": {"Text": {"Data": "Email de prueba"}}
  }'

# Ver emails enviados (LocalStack guarda un log)
curl -s http://localhost:4566/_localstack/ses/ | jq .
```

## 6. Verificar mensajes en DLQ (tras 3 fallos)

```bash
# Ver mensajes en la DLQ
awslocal sqs receive-message \
  --queue-url http://localhost:4566/000000000000/credit-eval-notif-dlq \
  --max-number-of-messages 10 | jq .
```

## 7. Script de reset (reiniciar LocalStack limpio)

```bash
# Si se necesita empezar desde cero:
docker compose -f docker-compose.infra.yml restart localstack
# El init.sh se ejecuta automáticamente al arrancar
```

## Estado esperado al finalizar
- [ ] Cola `credit-evaluation-notifications` existe con VisibilityTimeout=60 y redrive=3
- [ ] Cola `credit-eval-notif-dlq` existe
- [ ] Email `noreply@banco.com` verificado en SES
- [ ] Mensaje de prueba publicado y visible en la cola
- [ ] `ms-notifications` (paso 08) consume el mensaje y lo elimina de la cola
- [ ] Cola queda vacía tras el consumo exitoso
