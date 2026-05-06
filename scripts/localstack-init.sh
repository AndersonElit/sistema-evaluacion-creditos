#!/bin/bash
set -e

# ──────────────────────────────────────────────────────────────
# SQS
# ──────────────────────────────────────────────────────────────
echo "[LocalStack] Creando colas SQS..."

awslocal sqs create-queue \
  --queue-name credit-eval-notif-dlq \
  --attributes '{"MessageRetentionPeriod":"1209600"}'

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/credit-eval-notif-dlq \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue \
  --queue-name credit-evaluation-notifications \
  --attributes "{\"VisibilityTimeout\":\"60\",\"ReceiveMessageWaitTimeSeconds\":\"20\",\"MessageRetentionPeriod\":\"86400\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}"

# ──────────────────────────────────────────────────────────────
# SES
# ──────────────────────────────────────────────────────────────
echo "[LocalStack] Verificando identidad SES..."
awslocal ses verify-email-identity --email-address noreply@banco.com

# ──────────────────────────────────────────────────────────────
# SSM Parameter Store — ms-credit-evaluation
# ──────────────────────────────────────────────────────────────
echo "[LocalStack] Creando parámetros SSM para ms-credit-evaluation..."

QUEUE_URL="http://localstack:4566/000000000000/credit-evaluation-notifications"
SQS_ENDPOINT="http://localstack:4566"
KC_BASE="http://keycloak:8080"

awslocal ssm put-parameter \
  --name "/banco/ms-credit-evaluation/quarkus.datasource.username" \
  --value "postgres" --type String --overwrite

awslocal ssm put-parameter \
  --name "/banco/ms-credit-evaluation/quarkus.datasource.password" \
  --value "postgres" --type SecureString --overwrite

awslocal ssm put-parameter \
  --name "/banco/ms-credit-evaluation/quarkus.datasource.reactive.url" \
  --value "postgresql://postgres-credits:5432/creditos_db" --type String --overwrite

awslocal ssm put-parameter \
  --name "/banco/ms-credit-evaluation/quarkus.rest-client.risk-service.url" \
  --value "http://ms-risk:8081" --type String --overwrite

awslocal ssm put-parameter \
  --name "/banco/ms-credit-evaluation/mp.jwt.verify.publickey.location" \
  --value "${KC_BASE}/realms/banco/protocol/openid-connect/certs" --type String --overwrite

awslocal ssm put-parameter \
  --name "/banco/ms-credit-evaluation/mp.jwt.verify.issuer" \
  --value "http://localhost:9000/realms/banco" --type String --overwrite

awslocal ssm put-parameter \
  --name "/banco/ms-credit-evaluation/quarkus.sqs.endpoint-override" \
  --value "${SQS_ENDPOINT}" --type String --overwrite

awslocal ssm put-parameter \
  --name "/banco/ms-credit-evaluation/sqs.queue.url" \
  --value "${QUEUE_URL}" --type String --overwrite

# ──────────────────────────────────────────────────────────────
# SSM Parameter Store — ms-notifications
# ──────────────────────────────────────────────────────────────
echo "[LocalStack] Creando parámetros SSM para ms-notifications..."

awslocal ssm put-parameter \
  --name "/banco/ms-notifications/quarkus.datasource.username" \
  --value "postgres" --type String --overwrite

awslocal ssm put-parameter \
  --name "/banco/ms-notifications/quarkus.datasource.password" \
  --value "postgres" --type SecureString --overwrite

awslocal ssm put-parameter \
  --name "/banco/ms-notifications/quarkus.datasource.reactive.url" \
  --value "postgresql://postgres-notifications:5432/notifications_db" --type String --overwrite

awslocal ssm put-parameter \
  --name "/banco/ms-notifications/quarkus.sqs.endpoint-override" \
  --value "${SQS_ENDPOINT}" --type String --overwrite

awslocal ssm put-parameter \
  --name "/banco/ms-notifications/sqs.queue.url" \
  --value "${QUEUE_URL}" --type String --overwrite

awslocal ssm put-parameter \
  --name "/banco/ms-notifications/quarkus.ses.endpoint-override" \
  --value "${SQS_ENDPOINT}" --type String --overwrite

awslocal ssm put-parameter \
  --name "/banco/ms-notifications/aws.ses.from.email" \
  --value "noreply@banco.com" --type String --overwrite

echo "[LocalStack] Inicialización completa."
awslocal sqs list-queues
awslocal ssm get-parameters-by-path --path "/banco/" --recursive \
  --query 'Parameters[*].{Name:Name,Type:Type}' --output table
