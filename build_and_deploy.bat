@echo off
echo ===================================================
echo [1/2] Building project and copying dependencies...
echo ===================================================
call gradlew clean build -x test
if %errorlevel% neq 0 (
    echo [ERROR] Gradle build failed!
    exit /b %errorlevel%
)

echo.
echo ===================================================
echo [2/2] Building and restarting Docker containers...
echo ===================================================
call docker compose -f hazelcast-dcoker.yml up --build -d
if %errorlevel% neq 0 (
    echo [ERROR] Docker Compose failed!
    exit /b %errorlevel%
)

echo.
echo ===================================================
echo [SUCCESS] Build and Deployment Completed!
echo ===================================================
call docker ps
