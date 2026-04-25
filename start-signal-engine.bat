@echo off
title Signal Engine — Starting...

:: Wait for Windows to fully boot and Docker to start
:: Adjust the timeout if Docker takes longer on your machine
echo [Signal Engine] Waiting for system to fully boot...
timeout /t 30 /nobreak > nul

:: Wait for Docker Desktop to be running
echo [Signal Engine] Waiting for Docker Desktop...
:wait_docker
docker info > nul 2>&1
if %errorlevel% neq 0 (
    echo [Signal Engine] Docker not ready yet, waiting 10 more seconds...
    timeout /t 10 /nobreak > nul
    goto wait_docker
)

echo [Signal Engine] Docker is ready!

:: Go to the project folder
cd /d D:\signal-engine

:: Start all containers (--build rebuilds if code changed, -d runs in background)
echo [Signal Engine] Starting containers...
docker compose up --build -d

if %errorlevel% equ 0 (
    echo [Signal Engine] All containers started successfully!
    echo [Signal Engine] Trade Tracker: http://localhost
    echo [Signal Engine] Java API:      http://localhost:8080
    echo [Signal Engine] Python:        http://localhost:5000/health
) else (
    echo [Signal Engine] ERROR — something went wrong. Check Docker Desktop.
    pause
)

:: Close this window after 5 seconds (remove this line if you want it to stay open)
timeout /t 5 /nobreak > nul
