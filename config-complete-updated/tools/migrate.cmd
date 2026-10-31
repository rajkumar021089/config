@echo off
REM Run from the delivery root. JAVA_HOME may point to the IS-supported JDK.
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java.exe" "%~dp0MigrateConfig.java" %*
) else (
  java "%~dp0MigrateConfig.java" %*
)
