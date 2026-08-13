@echo off
setlocal
cd /d %~dp0

where mvn >nul 2>nul
if errorlevel 1 (
    if exist "D:\tools\apache-maven-3.9.9\bin\mvn.cmd" (
        set "MAVEN_CMD=D:\tools\apache-maven-3.9.9\bin\mvn.cmd"
    ) else (
        echo [SmartKB] Maven 3.9+ was not found. Add mvn to PATH and try again.
        pause
        exit /b 1
    )
) else (
    set "MAVEN_CMD=mvn"
)

echo [SmartKB] Starting at http://localhost:8081
echo [SmartKB] Demo account: demo / 123456
call "%MAVEN_CMD%" spring-boot:run
