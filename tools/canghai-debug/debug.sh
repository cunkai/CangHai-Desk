#!/bin/sh
# 沧海一键调试工具（Linux / macOS）
# 启动本地服务器并打开默认浏览器。必须走 http，file:// 无法注入桩。
set -e
cd "$(dirname "$0")"

if ! command -v node >/dev/null 2>&1; then
  echo "需要 Node.js（https://nodejs.org），没装先装一个再跑。"
  exit 1
fi

PORT="${CANGHAI_DEBUG_PORT:-8931}"
URL="http://127.0.0.1:$PORT/"

node server.js "$PORT" &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' INT TERM EXIT

# 等服务器起来再开浏览器
i=0
while [ "$i" -lt 30 ]; do
  if node -e "require('http').get('$URL',r=>process.exit(0)).on('error',()=>process.exit(1))" 2>/dev/null; then
    break
  fi
  sleep 0.3
  i=$((i + 1))
done

echo ""
echo "沧海调试工具：$URL"
echo "左手机 · 右大屏 · 假原生层。按 Ctrl+C 停止。"
echo ""

if command -v xdg-open >/dev/null 2>&1; then
  xdg-open "$URL" >/dev/null 2>&1 &
elif command -v open >/dev/null 2>&1; then
  open "$URL" >/dev/null 2>&1 &
else
  echo "打不开默认浏览器，请手动打开上面的地址。"
fi

wait $SERVER_PID
