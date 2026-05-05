#!/bin/bash
set -e

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

echo "[LocalStack] Verificando identidad SES..."
awslocal ses verify-email-identity --email-address noreply@banco.com

echo "[LocalStack] Inicialización completa."
awslocal sqs list-queues
