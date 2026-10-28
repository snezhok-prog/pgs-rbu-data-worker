@echo off
setlocal
chcp 65001 >nul
if not defined JAVA_HOME (
  echo Set JAVA_HOME to a JDK 25 installation.
  exit /b 1
)
if not exist "%JAVA_HOME%\bin\javac.exe" (
  echo JAVA_HOME must point to a JDK, not a JRE.
  exit /b 1
)
pushd "%~dp0..\.."
if /i "%~1"=="build" goto build
if not exist "runtime\dev-cli\classes\ru\evolenta\pgs\dev\DevCli.class" (
  echo Run tools\dev-cli\dev.cmd build first.
  popd
  exit /b 1
)
"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "local-runner\target\classes;local-runner\target\lib\*;runtime\dev-cli\classes" ru.evolenta.pgs.dev.DevCli %*
set "RBU_EXIT=%ERRORLEVEL%"
popd
exit /b %RBU_EXIT%
:build
"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 "tools\dev-cli\support\BuildDevCli.java"
set "RBU_EXIT=%ERRORLEVEL%"
popd
exit /b %RBU_EXIT%
