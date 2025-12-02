#!/bin/bash
cd /home/ubuntu/app

SPRING_DATASOURCE_URL=$(aws ssm get-parameter --name "/billie/prod/SPRING_DATASOURCE_URL" --with-decryption --query "Parameter.Value" --output text)
SPRING_DATASOURCE_USERNAME=$(aws ssm get-parameter --name "/billie/prod/SPRING_DATASOURCE_USERNAME" --with-decryption --query "Parameter.Value" --output text)
SPRING_DATASOURCE_PASSWORD=$(aws ssm get-parameter --name "/billie/prod/SPRING_DATASOURCE_PASSWORD" --with-decryption --query "Parameter.Value" --output text)
SPRING_DATA_MONGODB_URI=$(aws ssm get-parameter --name "/billie/prod/SPRING_DATA_MONGODB_URI" --with-decryption --query "Parameter.Value" --output text)
SPRING_DATA_REDIS_HOST=$(aws ssm get-parameter --name "/billie/prod/SPRING_DATA_REDIS_HOST" --with-decryption --query "Parameter.Value" --output text)
SPRING_DATA_REDIS_PORT=$(aws ssm get-parameter --name "/billie/prod/SPRING_DATA_REDIS_PORT" --with-decryption --query "Parameter.Value" --output text)
SPRING_ELASTICSEARCH_URIS=$(aws ssm get-parameter --name "/billie/prod/SPRING_ELASTICSEARCH_URIS" --with-decryption --query "Parameter.Value" --output text)
SPRING_RABBITMQ_ADDRESSES=$(aws ssm get-parameter --name "/billie/prod/SPRING_RABBITMQ_ADDRESSES" --with-decryption --query "Parameter.Value" --output text)
SPRING_RABBITMQ_USERNAME=$(aws ssm get-parameter --name "/billie/prod/SPRING_RABBITMQ_USERNAME" --with-decryption --query "Parameter.Value" --output text)
SPRING_RABBITMQ_PASSWORD=$(aws ssm get-parameter --name "/billie/prod/SPRING_RABBITMQ_PASSWORD" --with-decryption --query "Parameter.Value" --output text)
KAKAO_CLIENT_ID=$(aws ssm get-parameter --name "/billie/prod/SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KAKAO_CLIENT-ID" --with-decryption --query "Parameter.Value" --output text)
KAKAO_CLIENT_SECRET=$(aws ssm get-parameter --name "/billie/prod/SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KAKAO_CLIENT-SECRET" --with-decryption --query "Parameter.Value" --output text)
GEMINI_PROJECT_ID=$(aws ssm get-parameter --name "/billie/prod/CUSTOM_GOOGLE_AI_GEMINI_PROJECT-ID" --with-decryption --query "Parameter.Value" --output text)
FINTECH_API_KEY=$(aws ssm get-parameter --name "/billie/prod/CUSTOM_FINTECH_APIKEY" --with-decryption --query "Parameter.Value" --output text)
AWS_ACCESS_KEY_ID=$(aws ssm get-parameter --name "/billie/prod/AWS_ACCESS_KEY_ID" --with-decryption --query "Parameter.Value" --output text)
AWS_SECRET_KEY_ID=$(aws ssm get-parameter --name "/billie/prod/AWS_SECRET_KEY_ID" --with-decryption --query "Parameter.Value" --output text)

cat > .env << EOF
SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL}
SPRING_DATASOURCE_USERNAME=${SPRING_DATASOURCE_USERNAME}
SPRING_DATASOURCE_PASSWORD=${SPRING_DATASOURCE_PASSWORD}
SPRING_DATA_MONGODB_URI=${SPRING_DATA_MONGODB_URI}
SPRING_DATA_REDIS_HOST=${SPRING_DATA_REDIS_HOST}
SPRING_DATA_REDIS_PORT=${SPRING_DATA_REDIS_PORT}
SPRING_RABBITMQ_ADDRESSES=${SPRING_RABBITMQ_ADDRESSES}
SPRING_ELASTICSEARCH_URIS=${SPRING_ELASTICSEARCH_URIS}
SPRING_RABBITMQ_HOST=${SPRING_RABBITMQ_HOST}
SPRING_RABBITMQ_USERNAME=${SPRING_RABBITMQ_USERNAME}
SPRING_RABBITMQ_PASSWORD=${SPRING_RABBITMQ_PASSWORD}
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KAKAO_CLIENT-ID=${KAKAO_CLIENT_ID}
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KAKAO_CLIENT-SECRET=${KAKAO_CLIENT_SECRET}
CUSTOM_GOOGLE_AI_GEMINI_PROJECT-ID=${GEMINI_PROJECT_ID}
CUSTOM_FINTECH_APIKEY=${FINTECH_API_KEY}
CLOUD_AWS_CREDENTIALS_ACCESS-KEY=${AWS_ACCESS_KEY_ID}
CLOUD_AWS_CREDENTIALS_SECRET-KEY=${AWS_SECRET_KEY_ID}
EOF

aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin 235969061926.dkr.ecr.ap-northeast-2.amazonaws.com

docker pull 235969061926.dkr.ecr.ap-northeast-2.amazonaws.com/billie-app:latest

docker compose up -d --force-recreate