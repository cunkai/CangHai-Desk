/* 沧海一键调试工具 · 静态服务器（Node 内置模块，零依赖）
 *
 * 用法：node server.js [端口] [asset 根目录]
 *   端口默认 8931（环境变量 CANGHAI_DEBUG_PORT 可改）
 *   根目录默认先找仓库内相对位置 ../../app/src/main/assets（工具放在
 *   tools/canghai-debug/ 时），找不到再找沙箱检出；环境变量
 *   CANGHAI_DEBUG_ROOT / 第 2 个参数永远优先。
 *
 * 服务 asset 树，并在三个页面各自脚本执行前以内联 <script> 注入桩：
 *   phonehome/index.html、canghai/desktop/index.html、canghai/dock/index.html
 * 注入点：第一个 <script> 标签之前；没有 script 标签时改放 </head> 之前。
 * 壳页在 /，桩与假核心在 /__ch/ 下（仅供壳页加载，注入走内联）。
 */
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = parseInt(process.argv[2] || process.env.CANGHAI_DEBUG_PORT || '8931', 10);
/* 默认根目录：优先仓库内相对位置（工具放在 tools/canghai-debug/ 时），
   其次本机沙箱里的检出；两个都不存在时取第一个，让报错指明期望位置。 */
const ROOT_CANDIDATES = [
  path.resolve(__dirname, '..', '..', 'app', 'src', 'main', 'assets'),
  path.join(os.homedir(), 'workspace', 'review', 'CangHai-Desk', 'app', 'src', 'main', 'assets')
];
const DEFAULT_ROOT = ROOT_CANDIDATES.find(p => { try { return fs.statSync(p).isDirectory(); } catch (e) { return false; } }) || ROOT_CANDIDATES[0];
const ROOT = path.resolve(process.argv[3] || process.env.CANGHAI_DEBUG_ROOT || DEFAULT_ROOT);
const HERE = __dirname;

if (!fs.existsSync(ROOT)) {
  console.error('资源根目录不存在：' + ROOT);
  console.error('把工具放在仓库的 tools/canghai-debug/ 下，或用 CANGHAI_DEBUG_ROOT 指定 app/src/main/assets 的位置。');
  process.exit(1);
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.wasm': 'application/wasm',
  '.mp3': 'audio/mpeg',
  '.wav': 'audio/wav',
  '.mp4': 'video/mp4',
  '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8'
};

/* 注入目标：路径（去掉开头的 /）→ 页面标记 */
const INJECT = {
  'phonehome/index.html': 'phone',
  'canghai/desktop/index.html': 'desktop',
  'canghai/dock/index.html': 'dock'
};

let fakeCoreSrc, stubSrc;
try {
  fakeCoreSrc = fs.readFileSync(path.join(HERE, 'fake-core.js'), 'utf8');
  stubSrc = fs.readFileSync(path.join(HERE, 'stub.js'), 'utf8');
} catch (e) {
  console.error('读桩文件失败：' + e.message);
  process.exit(1);
}
for (const [name, src] of [['fake-core.js', fakeCoreSrc], ['stub.js', stubSrc]]) {
  if (src.toLowerCase().includes('</script')) {
    console.error(name + ' 里有 </script> 字面量，内联注入会被截断，先修掉再启动。');
    process.exit(1);
  }
}

function inject(html, page) {
  /* Dock 页没有 color-scheme 声明：父页声明 dark 时，子页视口会被刷成
     不透明白底、盖住桌面（已实测的坑）。页面自带声明时不动它。 */
  const needsScheme = !/name=["']color-scheme["']/i.test(html);
  const marker = (needsScheme ? '<meta name="color-scheme" content="dark">' : '') +
    '<script>window.__CH_PAGE=' + JSON.stringify(page) + ';\n' +
    fakeCoreSrc + '\n' + stubSrc + '\n</script>';
  const si = html.search(/<script[\s>]/i);
  if (si >= 0) return html.slice(0, si) + marker + html.slice(si);
  const hi = html.search(/<\/head>/i);
  if (hi >= 0) return html.slice(0, hi) + marker + html.slice(hi);
  return marker + html;
}

function send(res, code, body, type) {
  res.writeHead(code, {
    'Content-Type': type || 'text/plain; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Length': Buffer.byteLength(body)
  });
  res.end(body);
}

const server = http.createServer((req, res) => {
  try {
    let pathname;
    try {
      pathname = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    } catch (e) {
      send(res, 400, 'bad url');
      return;
    }
    pathname = pathname.replace(/\/+/g, '/');

    /* 壳页 */
    if (pathname === '/' || pathname === '/index.html') {
      fs.readFile(path.join(HERE, 'shell.html'), (e, d) => {
        if (e) { send(res, 500, 'shell.html missing'); return; }
        send(res, 200, d, MIME['.html']);
      });
      return;
    }
    /* 壳页加载的假核心 / 桩（注入到三个页面时走内联，不走这里） */
    if (pathname === '/__ch/fake-core.js') { send(res, 200, fakeCoreSrc, MIME['.js']); return; }
    if (pathname === '/__ch/stub.js') { send(res, 200, stubSrc, MIME['.js']); return; }

    /* asset 树 */
    const rel = pathname.replace(/^\//, '');
    const full = path.resolve(ROOT, rel);
    if (full !== ROOT && !full.startsWith(ROOT + path.sep)) {
      send(res, 403, 'forbidden');
      return;
    }
    fs.stat(full, (e, st) => {
      if (e) { send(res, 404, 'not found: ' + pathname); return; }
      let file = full;
      if (st.isDirectory()) file = path.join(full, 'index.html');
      const page = INJECT[path.relative(ROOT, file).split(path.sep).join('/')];
      if (page) {
        fs.readFile(file, 'utf8', (e2, html) => {
          if (e2) { send(res, 404, 'not found: ' + pathname); return; }
          send(res, 200, inject(html, page), MIME['.html']);
        });
        return;
      }
      fs.readFile(file, (e2, data) => {
        if (e2) { send(res, 404, 'not found: ' + pathname); return; }
        send(res, 200, data, MIME[path.extname(file).toLowerCase()] || 'application/octet-stream');
      });
    });
  } catch (e) {
    send(res, 500, 'server error: ' + e.message);
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log('沧海调试工具已启动');
  console.log('  地址：http://127.0.0.1:' + PORT + '/');
  console.log('  资源：' + ROOT);
  console.log('按 Ctrl+C 停止。');
});
server.on('error', (e) => {
  console.error('启动失败：' + e.message);
  process.exit(1);
});
