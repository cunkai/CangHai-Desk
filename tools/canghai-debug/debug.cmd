@echo off
rem 沧海一键调试工具（Windows）
rem 启动本地服务器并打开默认浏览器。必须走 http，file:// 无法注入桩。
cd /d "%~dp0"

where node >nul 2>&1
if errorlevel 1 (
  echo 需要 Node.js（https://nodejs.org），没装先装一个再跑。
  pause
  exit /b 1
)

if "%CANGHAI_DEBUG_PORT%"=="" set CANGHAI_DEBUG_PORT=8931
set URL=http://127.0.0.1:%CANGHAI_DEBUG_PORT%/

echo.
echo 沧海调试工具：%URL%
echo 左手机 · 右大屏 · 假原生层。关掉这个窗口即停止服务。
echo.

rem 先起一个延时开浏览器的后台任务，服务器在前台跑，Ctrl+C 停止。
start "" /min cmd /c "timeout /t 2 /nobreak >nul & start "" "%URL%""
node server.js %CANGHAI_DEBUG_PORT%
