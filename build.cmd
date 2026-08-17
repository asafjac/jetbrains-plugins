@echo off
rem Builds without a JDK installed. See build.sh for why this exists: Gradle needs a JVM before
rem it can start, and a JetBrains IDE bundles a real one rather than installing it system-wide.
setlocal enabledelayedexpansion

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :run

for %%R in (
  "%LOCALAPPDATA%\Programs"
  "%LOCALAPPDATA%\JetBrains\Toolbox\apps"
  "%ProgramFiles%\JetBrains"
) do (
  if exist "%%~R" (
    for /f "delims=" %%D in ('dir /b /ad /o-d "%%~R" 2^>nul') do (
      if exist "%%~R\%%D\jbr\bin\java.exe" (
        set "JAVA_HOME=%%~R\%%D\jbr"
        goto :run
      )
      for /f "delims=" %%E in ('dir /b /ad /o-d "%%~R\%%D" 2^>nul') do (
        if exist "%%~R\%%D\%%E\jbr\bin\java.exe" (
          set "JAVA_HOME=%%~R\%%D\%%E\jbr"
          goto :run
        )
      )
    )
  )
)

where java >nul 2>nul && goto :run
echo No JDK found. Install any JetBrains IDE ^(its bundled runtime is enough^), or set JAVA_HOME.
exit /b 1

:run
if defined JAVA_HOME echo Using JDK: %JAVA_HOME%
set "ARGS=%*"
if "%ARGS%"=="" set "ARGS=buildPlugin"
call gradlew.bat "-Porg.gradle.java.installations.paths=%JAVA_HOME%" %ARGS%
