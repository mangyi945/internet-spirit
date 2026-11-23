@echo off
chcp 65001 >nul
cd /d "%~dp0"
title 互联网精神与世长存

set "PY="
where py >nul 2>nul && set "PY=py"
if not defined PY (
  where python >nul 2>nul && set "PY=python"
)
if not defined PY (
  echo.
  echo   没找到 Python。先装一个：https://www.python.org/downloads/
  echo   装的时候记得勾 Add Python to PATH。
  echo.
  pause
  exit /b 1
)

echo.
echo   启动中，端口 %1（不给就用默认 8642）
echo   关掉这个窗口就停止。
echo.

start "中枢" /min %PY% serve.py %*
timeout /t 2 >nul
start "" http://127.0.0.1:8642/
echo.
echo   本机：http://127.0.0.1:8642/
echo   手机：http://本机内网IP:8642/  （地址看下面服务端打印）
echo.
pause
