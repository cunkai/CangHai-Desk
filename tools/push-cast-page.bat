@echo off
rem ------------------------------------------------------------------
rem 热更新网页接收端：改完 assets/cast/index.html 后执行本脚本，
rem 手机上立刻生效，不需要重新编译 / 安装 APK。
rem
rem 用法：  tools\push-cast-page.bat 192.168.0.103:8899 123456
rem        参数 1 = 手机 IP:端口（默认 192.168.0.103:8899）
rem        参数 2 = 手机上「WiFi 投屏（调试）」面板显示的 6 位会话 PIN
rem ------------------------------------------------------------------
setlocal
set IP=%~1
if "%IP%"=="" set IP=192.168.0.103:8899
set PIN=%~2
set PAGE=%~dp0..\app\src\main\assets\cast\index.html

if not exist "%PAGE%" (
  echo [ERROR] 找不到网页文件: %PAGE%
  exit /b 1
)
if "%PIN%"=="" echo [WARN] 未带 PIN，服务器会返回 403；请从手机面板复制 6 位 PIN 作为第二个参数

echo Pushing %PAGE%
echo      to http://%IP%/page
curl -sS -X POST -H "Content-Type: text/html; charset=utf-8" -H "X-Cast-Token: %PIN%" --data-binary @"%PAGE%" "http://%IP%/page"
echo.
echo Done. 刷新浏览器页面即可看到新网页；
echo 想还原 APK 内置版： curl http://%IP%/page/reset
endlocal
