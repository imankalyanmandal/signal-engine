@echo off
title Signal Engine

:: Start Docker Desktop
start "" "C:\Program Files\Docker\Docker\Docker Desktop.exe"

:: Wait for Docker to be ready
:wait
timeout /t 5 /nobreak > nul
docker info > nul 2>&1
if %errorlevel% neq 0 goto wait

:: Start Signal Engine
cd /d D:\signal-engine
docker compose up --build -d