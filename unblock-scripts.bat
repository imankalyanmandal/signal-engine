@echo off
:: ── Unblock Signal Engine scripts ─────────────────────────────────────────────
:: Run this ONCE as Administrator after copying the files to D:\signal-engine\
:: This removes the "publisher could not be verified" warning permanently.
::
:: HOW TO RUN:
::   Right-click this file → Run as administrator
:: ─────────────────────────────────────────────────────────────────────────────

echo [Unblock] Removing SmartScreen warnings from Signal Engine scripts...

:: Unblock all .bat files in the signal-engine folder
for %%f in ("D:\signal-engine\*.bat") do (
    echo [Unblock] Unblocking %%f
    powershell -Command "Unblock-File -Path '%%f'"
)

echo.
echo [Unblock] Done! The publisher warning will no longer appear.
echo [Unblock] You can now double-click the .bat files normally.
echo.
pause
