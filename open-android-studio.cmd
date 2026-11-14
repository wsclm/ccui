@echo off
setlocal
for %%I in ("%~dp0..") do set "PARENT=%%~fI"
if exist Q:\CCUI-Android\settings.gradle.kts goto launch
subst Q: "%PARENT%"
if errorlevel 1 (
  echo Could not map Q:. Open this folder directly in Android Studio.
  pause
  exit /b 1
)
:launch
if exist "%PARENT%\..\andriostudio\bin\studio64.exe" (
  start "" "%PARENT%\..\andriostudio\bin\studio64.exe" Q:\CCUI-Android
) else (
  echo Open Q:\CCUI-Android in Android Studio.
  pause
)
