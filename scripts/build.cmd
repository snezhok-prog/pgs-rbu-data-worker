@echo off
setlocal
chcp 65001 >nul
if not defined JAVA_HOME (
  echo Set JAVA_HOME to your JDK 25 installation.
  exit /b 1
)
if not exist "%JAVA_HOME%\bin\javac.exe" (
  echo JAVA_HOME must point to a JDK, not a JRE.
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
where mvn.cmd >nul 2>nul
if errorlevel 1 (
  echo Add Apache Maven 3.9 bin directory to PATH.
  exit /b 1
)
pushd "%~dp0.."
call mvn.cmd -B -ntp -f local-runner\pom.xml clean verify %*
set "RBU_EXIT=%ERRORLEVEL%"
popd
exit /b %RBU_EXIT%
