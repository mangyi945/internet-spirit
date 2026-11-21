/* 共用 CDP 小工具：省掉每个探针脚本里那 30 行样板
 *
 * 用法：
 *   import { open } from './cdp.mjs';
 *   const p = await open({ port: 9300, urlPart: 'index.html' });   // 手机（先 adb reverse 9300）
 *   await p.goto('http://127.0.0.1:8642/index.html?debug=1', 6000);
 *   const v = await p.eval(`1+1`);
 *   await p.shot('E:/工/screens/xxx.png');
 *   console.log(JSON.stringify(v));
 *   p.close();
 *
 * 环境变量：PHONE_CDP_PORT / CDP_PORT 都没设时默认 9300
 */
import fs from 'node:fs';

export async function open(opts = {}) {
  const port = Number(opts.port || process.env.PHONE_CDP_PORT || process.env.CDP_PORT || 9300);
  const urlPart = opts.urlPart || 'index.html';
  const listUrl = 'http://127.0.0.1:' + port + '/json/list';
  let list = [];
  for (let i = 0; i < 30; i++) {
    try {
      list = await (await fetch(listUrl)).json();
      if (list.length) break;
    } catch (e) {}
    await new Promise(r => setTimeout(r, 500));
  }
  const page = list.find(x => x.type === 'page' && x.url.indexOf(urlPart) >= 0);
  if (!page) throw new Error('没有找到页面（port ' + port + '），先确认 adb reverse / 调试端口');

  const ws = new WebSocket(page.webSocketDebuggerUrl);
  let id = 0;
  const waits = new Map();
  const exceptions = [];
  ws.onmessage = e => {
    const m = JSON.parse(e.data);
    if (m.id && waits.has(m.id)) { waits.get(m.id)(m); waits.delete(m.id); return; }
    if (m.method === 'Runtime.exceptionThrown') {
      const d = m.params.exceptionDetails || {};
      exceptions.push(String(((d.exception && d.exception.description) || d.text || '')).split('\n')[0]);
    }
  };
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

  const send = (method, params) => new Promise(res => {
    const mid = ++id;
    waits.set(mid, res);
    ws.send(JSON.stringify({ id: mid, method, params }));
  });

  await send('Runtime.enable');
  await send('Page.enable');

  const api = {
    port, page, exceptions, send,
    async eval(expression) {
      const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
      if (r.result && r.result.exceptionDetails) {
        const d = r.result.exceptionDetails;
        return { __err: (d.exception && d.exception.description) || d.text };
      }
      return r.result && r.result.result ? r.result.result.value : r.result;
    },
    async goto(url, waitMs = 5000) {
      await send('Page.navigate', { url });
      await new Promise(r => setTimeout(r, waitMs));
    },
    async waitFor(expression, timeoutMs = 20000, stepMs = 500) {
      const until = Date.now() + timeoutMs;
      while (Date.now() < until) {
        if (await api.eval(expression)) return true;
        await new Promise(r => setTimeout(r, stepMs));
      }
      return false;
    },
    async shot(file) {
      const r = await send('Page.captureScreenshot', { format: 'png' });
      fs.writeFileSync(file, Buffer.from(r.result.data, 'base64'));
      return file;
    },
    async touch(x, y, holdMs = 100) {
      await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y, radiusX: 8, radiusY: 8, force: 1, id: 1 }] });
      await new Promise(r => setTimeout(r, holdMs));
      await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    },
    sleep: ms => new Promise(r => setTimeout(r, ms)),
    close() { try { ws.close(); } catch (e) {} },
  };
  return api;
}
