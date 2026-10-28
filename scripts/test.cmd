@echo off
setlocal
chcp 65001 >nul
if not defined JAVA_HOME (
  echo Set JAVA_HOME to your JDK 25 installation.
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
pushd "%~dp0.."
call mvn.cmd -B -ntp -f local-runner\pom.xml test %*
set "RBU_EXIT=%ERRORLEVEL%"
popd
exit /b %RBU_EXIT%
