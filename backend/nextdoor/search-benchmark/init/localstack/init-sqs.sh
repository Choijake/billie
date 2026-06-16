#!/bin/bash
# LocalStack 시작 후 SQS 큐 자동 생성
# DLQ를 먼저 생성한 뒤 본 큐에 RedrivePolicy를 연결한다.
# VisibilityTimeout=30s + maxReceiveCount=3 → 약 90초 내에 메시지가 DLQ로 이동.
# 이는 장애 주입 벤치마크에서 ES 다운 시 유실 재현에 사용된다.

echo "Creating SQS queues..."

ACCOUNT_ID="000000000000"
REGION="us-east-1"

# ─── 1) DLQ 먼저 생성 ─────────────────────────────────────────────────────────

awslocal sqs create-queue \
  --queue-name post-index-dlq.fifo \
  --attributes '{
    "FifoQueue": "true",
    "ContentBasedDeduplication": "false",
    "MessageRetentionPeriod": "86400"
  }'

# ─── 2) 본 큐 생성 (DLQ ARN 참조) ─────────────────────────────────────────────
# VisibilityTimeout: 30s — 장애 주입 벤치마크에서 빠른 DLQ 이동을 위해 단축
# maxReceiveCount: 3     — 3회 실패 후 DLQ로 이동

POST_INDEX_DLQ_ARN="arn:aws:sqs:${REGION}:${ACCOUNT_ID}:post-index-dlq.fifo"

awslocal sqs create-queue \
  --queue-name post-index.fifo \
  --attributes "{
    \"FifoQueue\": \"true\",
    \"ContentBasedDeduplication\": \"false\",
    \"DeduplicationScope\": \"messageGroup\",
    \"FifoThroughputLimit\": \"perMessageGroupId\",
    \"VisibilityTimeout\": \"30\",
    \"MessageRetentionPeriod\": \"3600\",
    \"RedrivePolicy\": \"{\\\"deadLetterTargetArn\\\":\\\"${POST_INDEX_DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"
  }"

awslocal sqs create-queue \
  --queue-name billie-indexer-queue \
  --attributes '{
    "VisibilityTimeout": "120",
    "MessageRetentionPeriod": "3600"
  }'

echo "SQS queues created:"
awslocal sqs list-queues
