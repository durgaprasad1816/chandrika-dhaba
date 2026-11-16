@echo off
cd /d "%~dp0"
if not exist target\chandrika-dhaba.jar (
  where mvn >nul 2>nul
  if not errorlevel 1 (
    echo Building with Maven ^(first time needs internet for Maven plugins^)...
    call mvn -q clean package
  )
)
set JAR=target\chandrika-dhaba.jar
if not exist %JAR% set JAR=dist\chandrika-dhaba.jar
java -jar %JAR% %*
pause
