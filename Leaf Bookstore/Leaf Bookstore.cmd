@echo off
setlocal
cd /d "%~dp0"
title Leaf Bookstore
where java >nul 2>nul
if errorlevel 1 (
  echo Java 21 or newer is required. Please install Java and try again.
  pause
  exit /b 1
)
echo.
echo   LEAF BOOKSTORE
 echo   React + Java Spring Boot
 echo.
echo   Once startup finishes, open http://127.0.0.1:8080
 echo   Keep this window open while using the store.
echo   Press Ctrl+C to stop the store.
echo.
java -jar "Leaf Bookstore.jar"
echo.
echo   Leaf Bookstore has stopped. If port 8080 is already in use,
echo   an existing copy may be running at http://127.0.0.1:8080
pause
