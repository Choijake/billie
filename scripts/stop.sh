#!/bin/bash
cd /home/ubuntu/app

if [ -f "docker-compose.yml" ]; then
    docker compose down
    docker rmi 235969061926.dkr.ecr.ap-northeast-2.amazonaws.com/billie-app:latest 2>/dev/null || true
fi