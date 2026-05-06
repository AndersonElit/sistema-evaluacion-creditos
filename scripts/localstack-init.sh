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
# SSM Parameter Store
# Se usa boto3 en lugar de awslocal porque AWS CLI v1 intenta
# hacer HTTP GET de cualquier valor que empiece con http://,
# lo que rompe parámetros con URLs internas (http://ms-risk:8081).
# ──────────────────────────────────────────────────────────────
echo "[LocalStack] Creando parámetros SSM..."

python3 << 'PYEOF'
import boto3

ssm = boto3.client(
    'ssm',
    endpoint_url='http://localhost:4566',
    region_name='us-east-1',
    aws_access_key_id='test',
    aws_secret_access_key='test',
)

params = [
    # ms-credit-evaluation
    ('/banco/ms-credit-evaluation/quarkus.datasource.username',              'postgres',                                                                  'String'),
    ('/banco/ms-credit-evaluation/quarkus.datasource.password',              'postgres',                                                                  'SecureString'),
    ('/banco/ms-credit-evaluation/quarkus.datasource.reactive.url',          'postgresql://postgres-credits:5432/creditos_db',                            'String'),
    ('/banco/ms-credit-evaluation/quarkus.rest-client.risk-service.url',     'http://ms-risk:8081',                                                       'String'),
    ('/banco/ms-credit-evaluation/mp.jwt.verify.publickey.location',         'http://keycloak:8080/realms/banco/protocol/openid-connect/certs',           'String'),
    ('/banco/ms-credit-evaluation/mp.jwt.verify.issuer',                     'http://localhost:9000/realms/banco',                                        'String'),
    ('/banco/ms-credit-evaluation/quarkus.sqs.endpoint-override',            'http://localstack:4566',                                                    'String'),
    ('/banco/ms-credit-evaluation/sqs.queue.url',                            'http://localstack:4566/000000000000/credit-evaluation-notifications',        'String'),
    # ms-notifications
    ('/banco/ms-notifications/quarkus.datasource.username',                  'postgres',                                                                  'String'),
    ('/banco/ms-notifications/quarkus.datasource.password',                  'postgres',                                                                  'SecureString'),
    ('/banco/ms-notifications/quarkus.datasource.reactive.url',              'postgresql://postgres-notifications:5432/notifications_db',                 'String'),
    ('/banco/ms-notifications/quarkus.sqs.endpoint-override',                'http://localstack:4566',                                                    'String'),
    ('/banco/ms-notifications/sqs.queue.url',                                'http://localstack:4566/000000000000/credit-evaluation-notifications',        'String'),
    ('/banco/ms-notifications/quarkus.ses.endpoint-override',                'http://localstack:4566',                                                    'String'),
    ('/banco/ms-notifications/aws.ses.from.email',                           'noreply@banco.com',                                                         'String'),
]

for name, value, ptype in params:
    ssm.put_parameter(Name=name, Value=value, Type=ptype, Overwrite=True)
    print(f'  OK  {name}')

print(f'\n[SSM] {len(params)} parámetros creados.')
PYEOF

echo "[LocalStack] Inicialización completa."
awslocal sqs list-queues
awslocal ssm get-parameters-by-path --path "/banco/" --recursive \
  --query 'Parameters[*].{Name:Name,Type:Type}' --output table
