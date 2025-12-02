#!/bin/bash
if [ -d "/home/ubuntu/app" ]; then
    rm -rf /home/ubuntu/app
fi
mkdir -p /home/ubuntu/app