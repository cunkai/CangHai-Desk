// CDP 实测：无头 Chrome 打开投屏页，截图 A → 注入点击 → 截图 B，对比是否更新。
// 用法: node tools/cast-cdp-test.js <PIN>
const http = require('http'), net = require('net'), crypto = require('crypto'), fs = require('fs');
const PIN = process.argv[2] || '';
const CAST_URL = 'http://192.168.0.102:8899/';
const CDP_PORT = 9333;

function getJson(path) {
  return new Promise((res, rej) => {
    http.get({ host: '127.0.0.1', port: CDP_PORT, path }, r => {
      let s = ''; r.on('data', c => s += c); r.on('end', () => res(JSON.parse(s)));
    }).on('error', rej);
  });
}

// 极简 WS 客户端（客户端帧不加掩码，本机 CDP/服务器均接受）
function cdpWs(url) {
  console.log('cdpWs:', url);
  return new Promise((res, rej) => {
    const key = crypto.randomBytes(16).toString('base64');
    const u = new URL(url);
    const sock = net.connect(u.port, '127.0.0.1');
    const reqs = new Map(); let acc = Buffer.alloc(0), hs = false, id = 0;
    sock.on('connect', () => {
      sock.write('GET ' + u.pathname + ' HTTP/1.1\r\nHost: ' + u.host +
        '\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ' + key +
        '\r\nSec-WebSocket-Version: 13\r\n\r\n');
    });
    sock.on('data', d => {
      acc = Buffer.concat([acc, d]);
      if (!hs) {
        const i = acc.indexOf('\r\n\r\n'); if (i < 0) return;
        hs = true; acc = acc.subarray(i + 4); res(api());
      }
      for (;;) {
        if (acc.length < 2) return;
        const op = acc[0] & 0x0f; let len = acc[1] & 0x7f, off = 2;
        if (len === 126) { if (acc.length < 4) return; len = acc.readUInt16BE(2); off = 4; }
        else if (len === 127) { if (acc.length < 10) return; len = Number(acc.readBigUInt64BE(2)); off = 10; }
        if (acc.length < off + len) return;
        const payload = acc.subarray(off, off + len); acc = acc.subarray(off + len);
        if (op === 1) {
          const m = JSON.parse(payload.toString());
          if (m.id && reqs.has(m.id)) { reqs.get(m.id)(m.result); reqs.delete(m.id); }
        }
      }
    });
    function api() {
      return {
        send(method, params) {
          return new Promise(r => {
            const i = ++id; reqs.set(i, r);
            const p = JSON.stringify({ id: i, method, params: params || {} });
            // RFC6455：客户端帧必须带掩码，否则 Chrome 直接断链
            const mask = crypto.randomBytes(4);
            const body = Buffer.from(p, 'utf8');
            for (let k = 0; k < body.length; k++) body[k] ^= mask[k & 3];
            const lenBytes = body.length < 126 ? 0 : body.length < 65536 ? 2 : 8;
            const head = Buffer.alloc(2 + lenBytes + 4);
            head[0] = 0x81;
            head[1] = 0x80 | (lenBytes === 0 ? body.length : lenBytes === 2 ? 126 : 127);
            if (lenBytes === 2) head.writeUInt16BE(body.length, 2);
            else if (lenBytes === 8) head.writeBigUInt64BE(BigInt(body.length), 2);
            mask.copy(head, 2 + lenBytes);
            sock.write(Buffer.concat([head, body]));
          });
        },
        close() { sock.end(); }
      };
    }
    sock.on('error', rej);
  });
}

// 往投屏服务注入一次点击
function injectClick(x, y) {
  return new Promise(resolve => {
    const sock = net.connect(8899, '192.168.0.102');
    let hs = false, acc = Buffer.alloc(0);
    const st = s => {
      const p = Buffer.from(s); const m = crypto.randomBytes(4);
      const h = Buffer.from([0x81, 0x80 | p.length]); const x2 = Buffer.from(p);
      for (let i = 0; i < x2.length; i++) x2[i] ^= m[i & 3];
      sock.write(Buffer.concat([h, m, x2]));
    };
    sock.on('connect', () => {
      const k = crypto.randomBytes(16).toString('base64');
      sock.write('GET /ws?k=' + PIN + ' HTTP/1.1\r\nHost: h\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ' + k + '\r\nSec-WebSocket-Version: 13\r\n\r\n');
    });
    sock.on('data', d => {
      acc = Buffer.concat([acc, d]);
      if (!hs) {
        const i = acc.indexOf('\r\n\r\n'); if (i < 0) return;
        hs = true;
        st(JSON.stringify({ t: 'pointer', a: 'move', x, y, b: 0, meta: 0 }));
        setTimeout(() => {
          st(JSON.stringify({ t: 'pointer', a: 'down', x, y, b: 1, meta: 0 }));
          setTimeout(() => { st(JSON.stringify({ t: 'pointer', a: 'up', x, y, b: 0, meta: 0 })); resolve(); }, 90);
        }, 70);
      }
    });
    setTimeout(resolve, 3000);
  });
}

(async () => {
  console.log('START pin=' + PIN);
  const list = await getJson('/json');
  console.log('targets:', list.length);
  const page = list.find(t => t.type === 'page');
  if (!page) throw new Error('无 page 目标');
  const c = await cdpWs(page.webSocketDebuggerUrl);
  await c.send('Page.enable');
  await c.send('Page.navigate', { url: CAST_URL });
  await new Promise(r => setTimeout(r, 7000));   // 等自动连接 + MJPEG 出图
  const a = await c.send('Page.captureScreenshot', { format: 'png' });

  await injectClick(0.287, 0.94);                // 点四宫格开启动台
  await new Promise(r => setTimeout(r, 4500));

  const b = await c.send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync('C:/Users/GoodBoy/AppData/Local/Temp/cdpA.png', Buffer.from(a.data, 'base64'));
  fs.writeFileSync('C:/Users/GoodBoy/AppData/Local/Temp/cdpB.png', Buffer.from(b.data, 'base64'));
  console.log('截图A', a.data.length, 'B', b.data.length, a.data === b.data ? '完全相同(未更新!)' : '不同(有更新)');

  const logs = await c.send('Runtime.evaluate', { expression: 'document.getElementById("log").innerText.slice(-600)' });
  console.log('--- 页面日志尾部 ---\n' + logs.result.value);
  const img = await c.send('Runtime.evaluate', { expression: 'JSON.stringify({w:document.getElementById("mjpeg").naturalWidth,h:document.getElementById("mjpeg").naturalHeight,complete:document.getElementById("mjpeg").complete})' });
  console.log('mjpeg img:', img.result.value);
  c.close(); process.exit(0);
})().catch(e => { console.error('ERR', e && e.message ? e.message : e); process.exit(1); });
