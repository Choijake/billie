#!/bin/bash
# LocalStack SQS 큐 초기화 (search-benchmark 패턴 동일)
set -euo pipefail

echo "[localstack] Creating SQS queues..."

awslocal sqs create-queue \
  --queue-name billie-indexer-queue \
  --region us-east-1

awslocal sqs create-queue \
  --queue-name post-index.fifo \
  --attributes FifoQueue=true,ContentBasedDeduplication=true \
  --region us-east-1

echo "[localstack] SQS queues ready."
