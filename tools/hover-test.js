/**
 * 投屏输入注入调试工具：直连手机 WS，模拟鼠标轨迹，用来复现 / 验证交互 bug。
 *
 * 用法：
 *   node tools/hover-test.js <host:port> <PIN> <命令...>
 * 命令：
 *   move <x> <y>          归一化坐标 0~1（b=0 hover，b=1 按下拖动）
 *   drag <x> <y>          按下状态的 move（b=1）
 *   down <x> <y> / up <x> <y>
 *   sleep <ms>
 * 示例：
 *   node tools/hover-test.js 192.168.0.103:8899 123456 move 0.5 0.93 sleep 1000 move 0.52 0.92
 */
'use strict';
const net = require('net');
const crypto = require('crypto');

const [host, port, pin] = [process.argv[2], Number((process.argv[2] || '').split(':')[1]), process.argv[3]];
const cmds = process.argv.slice(4);

if (!host || !pin || !cmds.length) {
  console.error('用法: node hover-test.js <host:port> <PIN> move 0.5 0.93 sleep 500 ...');
  process.exit(1);
}

const sock = net.connect(port, host.split(':')[0]);
let handshaken = false;
let acc = Buffer.alloc(0);

sock.on('connect', () => {
  const key = crypto.randomBytes(16).toString('base64');
  sock.write(
    `GET /ws?k=${encodeURIComponent(pin)} HTTP/1.1\r\n` +
    `Host: ${host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n` +
    `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`);
});

sock.on('data', (d) => {
  acc = Buffer.concat([acc, d]);
  if (!handshaken) {
    const i = acc.indexOf('\r\n\r\n');
    if (i < 0) return;
    const head = acc.subarray(0, i).toString();
    if (!/101/.test(head.split('\r\n')[0])) {
      console.error('握手失败:', head.split('\r\n')[0]);
      process.exit(2);
    }
    handshaken = true;
    acc = acc.subarray(i + 4);
    run().then(() => { console.log('完成'); process.exit(0); })
         .catch((e) => { console.error('出错:', e.message); process.exit(3); });
  }
});
sock.on('error', (e) => { console.error('连接错误:', e.message); process.exit(4); });

function sendText(str) {
  const payload = Buffer.from(str, 'utf8');
  const mask = crypto.randomBytes(4);
  let head;
  if (payload.length < 126) {
    head = Buffer.from([0x81, 0x80 | payload.length]);
  } else {
    head = Buffer.alloc(4);
    head[0] = 0x81; head[1] = 0x80 | 126;
    head.writeUInt16BE(payload.length, 2);
  }
  const masked = Buffer.from(payload);
  for (let i = 0; i < masked.length; i++) masked[i] ^= mask[i & 3];
  sock.write(Buffer.concat([head, mask, masked]));
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function run() {
  await sleep(200);
  for (let i = 0; i < cmds.length; i++) {
    const c = cmds[i];
    if (c === 'sleep') {
      await sleep(Number(cmds[++i]) || 0);
      continue;
    }
    if (c === 'key') {
      const kc = Number(cmds[++i]);
      sendText(JSON.stringify({ t: 'key', a: 'down', kc, meta: 0 }));
      await sleep(90);
      sendText(JSON.stringify({ t: 'key', a: 'up', kc, meta: 0 }));
      console.log(`-> key ${kc}`);
      await sleep(60);
      continue;
    }
    const a = c === 'drag' ? 'move' : c;
    const b = c === 'drag' ? 1 : (c === 'down' ? 1 : (c === 'up' ? 0 : 0));
    const x = Number(cmds[++i]), y = Number(cmds[++i]);
    const msg = JSON.stringify({ t: 'pointer', a, x, y, b, meta: 0 });
    sendText(msg);
    console.log(`-> ${a} x=${x} y=${y} b=${b}`);
    await sleep(60);
  }
  await sleep(150);
}
