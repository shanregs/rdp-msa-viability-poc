@echo off
REM Performance Test Runner for RDP MSA POC
REM Usage: run-perf-tests.bat [test-type]

setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set PROJECT_DIR=%SCRIPT_DIR%..

REM Default values
set TEST_TYPE=happy-path
set THREADS=50
set RAMPUP=10
set DURATION=60
set TARGET_RPS=50
set TARGET_HOST=localhost
set TARGET_PORT=8082

if "%1"=="" goto :usage_hint
if "%1"=="--help" goto :usage
if "%1"=="-h" goto :usage

set TEST_TYPE=%1

echo ==============================================
echo   RDP MSA POC Performance Test Runner
echo ==============================================
echo.

REM Check Maven
where mvn >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Maven not found. Please install Maven 3.8+
    exit /b 1
)

REM Check Java
where java >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Java not found. Please install Java 21+
    exit /b 1
)

echo [INFO] Running %TEST_TYPE% test...

if "%TEST_TYPE%"=="happy-path" goto :happy_path
if "%TEST_TYPE%"=="local-failure" goto :local_failure
if "%TEST_TYPE%"=="remote-failure" goto :remote_failure
if "%TEST_TYPE%"=="stress" goto :stress
goto :unknown

:happy_path
echo [INFO] Configuration: threads=50, rps=50, duration=60s
cd /d "%PROJECT_DIR%\performance-tests"
call mvn verify -Phappy-path -Dtarget.host=%TARGET_HOST% -Dtarget.port=%TARGET_PORT% -Dthreads=50 -Drampup=10 -Dduration=60 -Dtarget.rps=50
goto :done

:local_failure
echo [WARN] For local-failure test, stop the local reference-lookup-service:
echo        docker stop server1-reference-lookup-service
echo.
pause
echo [INFO] Configuration: threads=20, rps=20, duration=60s
cd /d "%PROJECT_DIR%\performance-tests"
call mvn verify -Plocal-failure -Dtarget.host=%TARGET_HOST% -Dtarget.port=%TARGET_PORT% -Dthreads=20 -Drampup=5 -Dduration=60 -Dtarget.rps=20
echo.
echo [INFO] Remember to restart stopped services:
echo        docker start server1-reference-lookup-service
goto :done

:remote_failure
echo [WARN] For remote-failure test, stop ALL reference-lookup-service instances:
echo        docker stop server1-reference-lookup-service
echo        docker stop server3-reference-lookup-service
echo.
pause
echo [INFO] Configuration: threads=20, rps=20, duration=60s
cd /d "%PROJECT_DIR%\performance-tests"
call mvn verify -Premote-failure -Dtarget.host=%TARGET_HOST% -Dtarget.port=%TARGET_PORT% -Dthreads=20 -Drampup=5 -Dduration=60 -Dtarget.rps=20
echo.
echo [INFO] Remember to restart stopped services:
echo        docker start server1-reference-lookup-service
echo        docker start server3-reference-lookup-service
goto :done

:stress
echo [INFO] Configuration: threads=100, rps=100, duration=120s
cd /d "%PROJECT_DIR%\performance-tests"
call mvn verify -Pstress -Dtarget.host=%TARGET_HOST% -Dtarget.port=%TARGET_PORT% -Dthreads=100 -Drampup=20 -Dduration=120 -Dtarget.rps=100
goto :done

:unknown
echo [ERROR] Unknown test type: %TEST_TYPE%
goto :usage

:usage_hint
echo Usage: run-perf-tests.bat [test-type]
echo.
echo Test Types:
echo   happy-path     Normal operation test (default)
echo   local-failure  Test passive health pattern with local instance down
echo   remote-failure Test circuit breaker with all instances down
echo   stress         High load test (100 rps)
echo.
echo Examples:
echo   run-perf-tests.bat happy-path
echo   run-perf-tests.bat local-failure
echo   run-perf-tests.bat stress
echo.
echo For more options, use Maven directly:
echo   cd performance-tests
echo   mvn verify -Phappy-path -Dthreads=100 -Dtarget.rps=100
goto :eof

:usage
echo Usage: run-perf-tests.bat [test-type]
echo.
echo Test Types:
echo   happy-path     Normal operation test (default)
echo   local-failure  Test passive health pattern with local instance down
echo   remote-failure Test circuit breaker with all instances down
echo   stress         High load test (100 rps)
echo.
goto :eof

:done
echo.
echo [INFO] Test complete. Reports available at:
echo        %PROJECT_DIR%\performance-tests\target\jmeter\reports\index.html
echo.
echo [INFO] Done!
