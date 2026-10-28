@echo off
setlocal
chcp 65001 >nul
if not defined JAVA_HOME (
  echo Set JAVA_HOME to your JDK 25 installation.
  exit /b 1
)
if "%~1"=="" (
  echo Usage: scripts\run-worker.cmd "FULL_PATH_TO_WORKER_PROPERTIES"
  exit /b 1
)
pushd "%~dp0.."
if not exist "local-runner\target\classes\ru\evolenta\pgs\worker\Main.class" (
  echo First run scripts\build.cmd
  popd
  exit /b 1
)
echo Java runs on this PC and polls the configured Camunda topic.
echo Stop with Ctrl+C. Preserve the state directory when restarting.
"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "local-runner\target\classes;local-runner\target\lib\*" ru.evolenta.pgs.worker.Main "%~1"
set "RBU_EXIT=%ERRORLEVEL%"
popd
exit /b %RBU_EXIT%
