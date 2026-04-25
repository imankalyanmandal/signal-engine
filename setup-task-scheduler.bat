@echo off
:: Run as Administrator — right-click → Run as administrator

net session > nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Run as administrator
    cmd /k
    exit /b 1
)

echo Removing old task...
schtasks /delete /tn "SignalEngine" /f > nul 2>&1

echo Creating task...
schtasks /create /tn "SignalEngine" /tr "D:\signal-engine\start-signal-engine.bat" /sc onstart /delay 0001:00 /ru "SYSTEM" /rl HIGHEST /f

if %errorlevel% equ 0 (
    echo.
    echo SUCCESS - Task created!
    schtasks /query /tn "SignalEngine" /fo LIST
) else (
    echo FAILED - Check you ran as Administrator
)

:: Keep window open indefinitely — user must close manually
echo.
echo Task setup complete. Close this window when done.
cmd /k