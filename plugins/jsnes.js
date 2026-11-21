/**
 * NES（红白机）模拟器插件 —— 基于 jsNES（纯 JavaScript，无需 WASM）
 *
 * 为什么不用 WASM 内核：EmulatorJS 那套需要跨源隔离头 + 线程支持，
 * 在 WebView 里极不稳定。纯 JS 没有这些依赖，代价是速度慢一些
 * （手机上大约能跑 30–50 帧）。
 *
 * 用法：游戏模式里点「NES」→ 选 .nes 文件 → 开玩
 * 键盘（电脑）：方向键 + Z(A) X(B) Enter(Start) Shift(Select)
 */

(function () {
  'use strict';

  const W = 256, H = 240;

  let nes = null;
  let cv = null, ctx = null;
  let off = null, octx = null, img = null;
  let running = false, rafId = 0;
  let lastT = 0, acc = 0;
  let audioCtx = null, audioNode = null, audioOn = false;
  let romName = '';
  let romBytes = null;   // 原始 ROM 留着：联机时要原样发给对方 / 重新开机
  let romKey = '';       // 存档按 ROM 内容认，不按文件名：改名不丢档、换 ROM 不串档

  // 跳帧
  let autoSkip = true;
  let skip = 0;             // 每 (skip+1) 帧画 1 帧
  let frameNo = 0;
  let emuAvg = 16, paintAvg = 3;
  let fps = 0, fpsCount = 0, fpsT = 0;
  let fpsEl = null;
  let buf32 = null;
  let lastHash = 0;      // 每帧画面的采样哈希，联机时用来对表
  let frameErrs = 0;     // 连续帧异常次数：防止出错后一直空转假装在跑

  /* ---------- 加载 jsNES（懒加载） ---------- */
  function loadLib() {
    return new Promise((resolve, reject) => {
      if (window.jsnes) return resolve(window.jsnes);
      const s = document.createElement('script');
      s.src = 'plugins/lib/jsnes.min.js';
      s.onload = () => window.jsnes ? resolve(window.jsnes) : reject(new Error('jsnes 未定义'));
      s.onerror = () => reject(new Error('无法加载 jsnes.min.js'));
      document.head.appendChild(s);
    });
  }

  /* ---------- 画面 ---------- */
  function ensureCanvas() {
    const g = HLJJS.api.game;
    cv = g.screen();
    if (!cv) throw new Error('拿不到游戏画布');
    ctx = cv.getContext('2d');
    if (!off) {
      off = document.createElement('canvas');
      off.width = W; off.height = H;
      octx = off.getContext('2d');
      img = octx.createImageData(W, H);
      buf32 = new Uint32Array(img.data.buffer);
    }
  }

  let pendingFb = null;   // 最新一帧的原始像素，等真正要画的时候再转换

  function paint() {
    if (!ctx) return;
    // 像素转换（61,440 次写 + 240KB putImageData）只在真的要画这一帧时做。
    // 原来的 onFrame 每个模拟帧都做一遍，跳帧根本省不掉 CPU。
    if (pendingFb && buf32) {
      const fb = pendingFb;
      pendingFb = null;
      for (let i = 0; i < fb.length; i++) buf32[i] = 0xFF000000 | fb[i];
      try { octx.putImageData(img, 0, 0); } catch (e) {}
    }
    ctx.imageSmoothingEnabled = false;
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, cv.width, cv.height);

    // 保持 256:240 比例，居中
    const s = Math.min(cv.width / W, cv.height / H);
    const dw = Math.round(W * s), dh = Math.round(H * s);
    ctx.drawImage(off, Math.round((cv.width - dw) / 2), Math.round((cv.height - dh) / 2), dw, dh);
  }

  function onFrame(fb) {
    if (!buf32) return;
    pendingFb = fb;    // 真正转换/上传留给 paint()，跳帧时才省得下 CPU
    // 对表哈希只有联机时才需要：单机算它纯浪费
    if (NET.on) {
      let h = 2166136261;
      for (let i = 0; i < fb.length; i += 61) h = ((h ^ fb[i]) * 16777619) >>> 0;
      lastHash = h >>> 0;
    }
  }

  function ensureFpsBadge() {
    if (fpsEl) return;
    const wrap = document.getElementById('gamewrap');
    if (!wrap) return;
    const d = document.createElement('div');
    d.id = 'nesfps';
    d.style.cssText = 'position:absolute;left:6px;top:6px;z-index:5;font-family:Consolas,monospace;' +
      'font-size:10px;color:#7cf07c;background:rgba(0,0,0,.55);padding:1px 5px;pointer-events:none';
    wrap.appendChild(d);
    fpsEl = d;
  }

  /* ---------- 声音 ----------
     APU 每秒回调约 88,200 个样本；原来是 push 到普通数组、播放回调里再逐样本 shift()。
     shift 要把整段数组往前搬，等于每秒在主线程上搬几千万个元素（O(n²)），
     听感/掉帧都来自这里。改成定长 Float32Array 环形缓冲：写入、读取都是 O(1)。 */
  const AUD_FRAMES = 8192;                       // 立体声帧数上限（48k 下约 170ms）
  const audRing = new Float32Array(AUD_FRAMES * 2);
  let audW = 0, audR = 0, audN = 0;              // 写指针 / 读指针 / 已缓存帧数

  function initAudio() {
    if (audioCtx) return;
    try {
      audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      audioNode = audioCtx.createScriptProcessor(2048, 0, 2);
      audioNode.onaudioprocess = e => {
        const l = e.outputBuffer.getChannelData(0);
        const r = e.outputBuffer.getChannelData(1);
        for (let i = 0; i < l.length; i++) {
          if (audN > 0) {
            const p = audR * 2;
            l[i] = audRing[p];
            r[i] = audRing[p + 1];
            audR = (audR + 1) % AUD_FRAMES;
            audN--;
          } else {
            l[i] = 0; r[i] = 0;
          }
        }
      };
      const gain = audioCtx.createGain();
      gain.gain.value = 0.12;
      audioNode.connect(gain);
      gain.connect(audioCtx.destination);
      audioOn = true;
    } catch (e) { audioOn = false; }
  }

  function onAudioSample(l, r) {
    if (!audioOn) return;
    if (audN >= AUD_FRAMES) { audR = audW; audN = 0; }   // 溢出就丢掉旧的（和原来 length = 0 等价）
    const p = audW * 2;
    audRing[p] = l;
    audRing[p + 1] = r;
    audW = (audW + 1) % AUD_FRAMES;
    audN++;
  }

  /* ---------- 主循环 ---------- */
  function loop(t) {
    if (!running) { rafId = 0; return; }
    rafId = requestAnimationFrame(loop);

    if (!lastT) lastT = t;
    acc += Math.min(100, t - lastT);
    lastT = t;

    // 60Hz：按累计时间跑帧。不能每帧都强行补 1 帧，否则 120Hz 屏幕上会跑成 120fps（游戏 2 倍速）
    const STEP = 1000 / 60;
    let n = Math.floor(acc / STEP);
    acc -= n * STEP;
    if (n > 3) n = 3;
    if (n < 1) return;

    const t0 = performance.now();
    for (let i = 0; i < n; i++) {
      try { nes.frame(); frameErrs = 0; } catch (e) { frameErrs++; dlogNav(e); break; }
    }
    if (frameErrs >= 4) {
      frameErrs = 0;
      try { stopLoop(); } catch (e) {}
      try { HLJJS.api.toast('模拟器卡住了，已暂停；重开一次游戏'); } catch (e) {}
      return;
    }
    const per = (performance.now() - t0) / n;
    emuAvg = emuAvg * 0.9 + per * 0.1;

    frameNo++;
    const doRender = (skip === 0) || (frameNo % (skip + 1) === 0);
    if (doRender) {
      const p0 = performance.now();
      paint();
      paintAvg = paintAvg * 0.9 + (performance.now() - p0) * 0.1;
      fpsCount++;
    }

    if (autoSkip) {
      if (emuAvg > 14 && skip < 4) skip++;
      else if (emuAvg < 9 && paintAvg < 6 && skip > 0) skip--;
    }

    const nowMs = performance.now();
    if (!fpsT) fpsT = nowMs;
    if (nowMs - fpsT >= 1000) {
      fps = Math.round(fpsCount * 1000 / (nowMs - fpsT));
      fpsCount = 0; fpsT = nowMs;
      if (fpsEl) fpsEl.textContent = fps + ' fps' + (skip > 0 ? ' · 跳 ' + skip : '');
    }
  }

  function dlogNav(e) {
    try { if (window.HLJJS && HLJJS.api && HLJJS.api.toast) HLJJS.api.toast('模拟器出错：' + e.message); } catch (x) {}
  }

  function startLoop() {
    if (!running) { running = true; lastT = 0; acc = 0; }
    // 音频上下文被停掉过就唤醒，否则恢复游戏没声音
    try { if (audioCtx && audioCtx.state === 'suspended') audioCtx.resume(); } catch (e) {}
    if (!rafId) rafId = requestAnimationFrame(loop);
  }

  function stopLoop() {
    if (NET.on) stopNet();
    running = false;
    if (rafId) { cancelAnimationFrame(rafId); rafId = 0; }
    pendingFb = null;
    // 停掉以后别让 ScriptProcessor 继续每秒回调 60 次空转
    try { if (audioCtx && audioCtx.state === 'running') audioCtx.suspend(); } catch (e) {}
  }

  /* ================= 联机（锁步同步） =================
     两边跑同一份 ROM、同一串帧，每帧交换一次按键。
     本地按键也要延迟 delay 帧生效 —— 不这样做两边会算出不同结果。 */
  const NET = {
    on: false, armed: false, role: 'host', delay: 6, frame: 0, peerCur: 0,
    send: null, onStatus: null,
    pad: 0, local: new Map(), remote: new Map(),
    raf: 0, lastT: 0, acc: 0, waitingSince: 0, lastReport: 0,
    sums: new Map(), peerSums: new Map(), desync: false,
  };
  // 按键位：up/down/left/right/A/B/start/select
  const TAG_BIT = {
    up: 1, down: 2, left: 4, right: 8,
    cross: 16, triangle: 16, circle: 32, square: 32,
    start: 64, select: 128,
  };

  function applyMask(player, mask) {
    const c = C();
    if (!c || !nes) return;
    const map = [c.BUTTON_UP, c.BUTTON_DOWN, c.BUTTON_LEFT, c.BUTTON_RIGHT,
                 c.BUTTON_A, c.BUTTON_B, c.BUTTON_START, c.BUTTON_SELECT];
    for (let i = 0; i < 8; i++) {
      const b = map[i];
      if (b == null) continue;
      try {
        if (mask & (1 << i)) nes.buttonDown(player, b);
        else nes.buttonUp(player, b);
      } catch (e) {}
    }
  }

  function netReport(force) {
    if (!NET.on) return;
    const now = performance.now();
    if (!force && now - NET.lastReport < 500) return;
    NET.lastReport = now;
    if (NET.onStatus) {
      try {
        NET.onStatus({
          frame: NET.frame, delay: NET.delay,
          waiting: NET.waitingSince > 0,
          waitMs: NET.waitingSince ? Math.round(now - NET.waitingSince) : 0,
          desync: NET.desync,
        });
      } catch (e) {}
    }
  }

  function checkSum(f) {
    const a = NET.sums.get(f), b = NET.peerSums.get(f);
    if (a == null || b == null) return;
    if ((a >>> 0) !== (b >>> 0) && !NET.desync) {
      NET.desync = true;
      try { HLJJS.api.toast('联机不同步了：两边画面已经对不上'); } catch (e) {}
      netReport(true);
    }
  }

  function netSum(frame, hash) {
    if (!NET.on) return;
    NET.peerSums.set(frame | 0, hash >>> 0);
    if (NET.peerSums.size > 240) {
      const cut = NET.frame - 120;
      NET.peerSums.forEach((v, k) => { if (k < cut) NET.peerSums.delete(k); });
    }
    checkSum(frame | 0);
  }

  function netBoot(lib) {
    if (!lib || !lib.NES) throw new Error('内核没加载');
    if (!romBytes || !romBytes.length) throw new Error('没有 ROM');
    ensureCanvas();
    nes = new lib.NES({ onFrame, onAudioSample, onStatusUpdate: function () {} });
    nes.loadROM(binString(romBytes));
    try { HLJJS.api.game.setHint(''); } catch (e) {}
    ensureFpsBadge();
    try { initAudio(); } catch (e) {}
    paint();
  }

  async function startNet(opts) {
    opts = opts || {};
    const lib = await loadLib();
    stopLoop();
    netBoot(lib);
    NET.on = true;
    NET.role = opts.role === 'guest' ? 'guest' : 'host';
    NET.delay = Math.max(2, Math.min(20, opts.delay || 6));
    NET.send = opts.send || null;
    NET.onStatus = opts.onStatus || null;
    NET.frame = 0;
    NET.peerCur = 0;
    NET.pad = 0;
    NET.local.clear();
    NET.remote.clear();
    NET.lastT = 0;
    NET.acc = 0;
    NET.waitingSince = 0;
    NET.lastReport = 0;
    NET.sums.clear();
    NET.peerSums.clear();
    NET.desync = false;
    NET.armed = false;
    running = true;
    if (NET.raf) cancelAnimationFrame(NET.raf);
    NET.raf = requestAnimationFrame(netLoop);
    netReport(true);
  }

  /** 双方都准备好后才真正开始跑帧：避免早到的输入被丢掉 */
  function armNet() {
    if (!NET.on) return;
    NET.armed = true;
    NET.lastT = 0;
    NET.acc = 0;
    NET.waitingSince = 0;
    netReport(true);
  }

  function netInput(frame, bits, cur) {
    if (!NET.on) return;
    if (cur != null) NET.peerCur = Math.max(NET.peerCur, cur | 0);
    NET.remote.set(frame | 0, bits & 255);
    if (NET.remote.size > 600) {
      const cut = NET.frame - 20;
      NET.remote.forEach((v, k) => { if (k < cut) NET.remote.delete(k); });
    }
  }

  function stopNet() {
    NET.on = false;
    NET.send = null;
    if (NET.raf) { cancelAnimationFrame(NET.raf); NET.raf = 0; }
    running = false;
  }

  function netLoop(t) {
    if (!NET.on) { NET.raf = 0; return; }
    NET.raf = requestAnimationFrame(netLoop);
    if (!NET.armed) { netReport(false); return; }

    if (!NET.lastT) NET.lastT = t;
    NET.acc += Math.min(200, t - NET.lastT);
    NET.lastT = t;
    const STEP = 1000 / 60;
    let budget = Math.floor(NET.acc / STEP);
    if (budget < 1) return;
    if (budget > 3) budget = 3;
    NET.acc -= budget * STEP;

    const me = NET.role === 'guest' ? 2 : 1;
    const peer = me === 1 ? 2 : 1;
    let ran = 0;

    for (let k = 0; k < budget; k++) {
      const f = NET.frame;
      // 不能跑到对方前面：对方确认执行到 peerCur，这边最多再往前 1 帧
      if (f > NET.peerCur + 1) break;
      // 前 delay 帧双方约定都不按键，所以不用等
      if (f >= NET.delay && !NET.remote.has(f)) break;

      const localBits = NET.local.get(f) || 0;
      const remoteBits = NET.remote.get(f) || 0;
      // 现在按着的键，约定给第 f+delay 帧（先算，别等自增后再加）
      const sendFrame = f + NET.delay;
      applyMask(me, localBits);
      applyMask(peer, remoteBits);
      try { nes.frame(); } catch (e) { dlogNav(e); break; }

      NET.frame++;
      ran++;

      NET.local.set(sendFrame, NET.pad);
      // 只清掉「已经用过」的帧；f 这一帧刚开始用，不能删
      NET.local.forEach((v, key) => { if (key < NET.frame - 1) NET.local.delete(key); });
      if (NET.send) {
        try { NET.send({ t: 'in', f: sendFrame, b: NET.pad, cur: NET.frame }); } catch (e) {}
      }

      // 每 60 帧对一次表，两边画面哈希不一致立刻报不同步
      if (f % 60 === 0) {
        NET.sums.set(f, lastHash);
        if (NET.send) {
          try { NET.send({ t: 'sum', f: f, h: lastHash }); } catch (e) {}
        }
        checkSum(f);
        if (NET.sums.size > 240) {
          const cut = NET.frame - 120;
          NET.sums.forEach((v, k) => { if (k < cut) NET.sums.delete(k); });
        }
      }
    }

    if (ran) {
      paint();
      NET.waitingSince = 0;
      netReport(false);
    } else {
      if (!NET.waitingSince) NET.waitingSince = performance.now();
      netReport(false);
      if (performance.now() - NET.waitingSince > 10000) {
        try { HLJJS.api.toast('联机：对方已经 10 秒没响应'); } catch (e) {}
        NET.waitingSince = performance.now();
      }
    }
  }

  /* ---------- 输入 ---------- */
  const C = () => (window.jsnes && window.jsnes.Controller) || {};

  function tagToButton(tag) {
    const c = C();
    switch (tag) {
      case 'up': return c.BUTTON_UP;
      case 'down': return c.BUTTON_DOWN;
      case 'left': return c.BUTTON_LEFT;
      case 'right': return c.BUTTON_RIGHT;
      case 'cross': return c.BUTTON_B;
      case 'circle': return c.BUTTON_A;
      case 'square': return c.BUTTON_B;
      case 'triangle': return c.BUTTON_A;
      case 'start': return c.BUTTON_START;
      case 'select': return c.BUTTON_SELECT;
      default: return null;
    }
  }

  function pressTag(tag, down) {
    // 联机中：只记按键，真正写进内核是 netLoop 按帧做的
    if (NET.on) {
      const bit = TAG_BIT[tag] || 0;
      if (!bit) return;
      if (down) NET.pad |= bit; else NET.pad &= ~bit;
      return;
    }
    if (!nes) return;
    const b = tagToButton(tag);
    if (b == null) return;
    try { down ? nes.buttonDown(1, b) : nes.buttonUp(1, b); } catch (e) {}
  }

  const KEYMAP = {
    ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right',
    z: 'circle', Z: 'circle', x: 'cross', X: 'cross',
    j: 'circle', J: 'circle', k: 'cross', K: 'cross',
    Enter: 'start', Shift: 'select', ' ': 'start',
  };

  function onKey(e, down) {
    if (!running) return;
    const tag = KEYMAP[e.key];
    if (!tag) return;
    e.preventDefault();
    pressTag(tag, down);
  }

  /* ---------- 存档 / 读档 ----------
     内核自带 toJSON/fromJSON（实测 Final Mission 状态 2.0 MB，
     fromJSON 之后画面哈希和存档点完全一致）。体积太大不能放 localStorage，
     所以走页面给的 IndexedDB 桥 window.HLJJS_NES_STORE。 */
  let lastState = '';

  function stateKey() { return romKey || ('nes:' + (romName || 'game')); }

  /** ROM 内容的 32 位指纹（FNV-1a 抽样）：存档用它当 key，改名不丢档、换 ROM 不串档 */
  function romKeyOf(u8) {
    let h = 2166136261;
    for (let i = 0; i < u8.length; i += 97) h = ((h ^ u8[i]) * 16777619) >>> 0;
    h = ((h ^ (u8.length & 255)) * 16777619) >>> 0;
    return 'nes:' + h.toString(16);
  }

  async function saveState() {
    if (!nes) { HLJJS.api.toast('先载入一个 ROM'); return { ok: false, err: 'no-rom' }; }
    if (NET.on) { HLJJS.api.toast('联机中不能存档'); return { ok: false, err: 'net' }; }
    try {
      const json = JSON.stringify(nes.toJSON());
      lastState = json;
      const bridge = window.HLJJS_NES_STORE;
      let stored = false;
      if (bridge && bridge.put) {
        stored = await bridge.put(stateKey(), { json: json, rom: romName });
        HLJJS.api.toast(stored ? ('已存档：' + romName + '（' + Math.round(json.length / 1024) + ' KB）')
                               : '存档失败：本机存储写不进去');
      } else {
        HLJJS.api.toast('已存档（只在本次运行有效：这个页面没有存储桥）');
      }
      return { ok: true, bytes: json.length, stored: stored, key: stateKey() };
    } catch (e) {
      HLJJS.api.toast('存档失败：' + e.message);
      return { ok: false, err: e.message };
    }
  }

  async function loadState() {
    if (!nes) { HLJJS.api.toast('先载入一个 ROM'); return { ok: false, err: 'no-rom' }; }
    if (NET.on) { HLJJS.api.toast('联机中不能读档'); return { ok: false, err: 'net' }; }
    let rec = null;
    let from = 'disk';
    try {
      const bridge = window.HLJJS_NES_STORE;
      if (bridge && bridge.get) rec = await bridge.get(stateKey());
    } catch (e) {}
    if (!rec && lastState) { rec = { json: lastState, rom: romName }; from = 'memory'; }
    if (!rec || !rec.json) { HLJJS.api.toast('这个游戏还没有存档'); return { ok: false, err: 'no-state' }; }
    // key 是按 ROM 内容算的，所以文件名不同也照样能读；只在旧的名字当 key 的存档上做一次核对
    if (!romKey && rec.rom && romName && rec.rom !== romName) {
      HLJJS.api.toast('存档是「' + rec.rom + '」的，和当前 ROM 不是同一个');
      return { ok: false, err: 'rom-mismatch' };
    }
    const wasRunning = running;
    try {
      stopLoop();
      nes.fromJSON(JSON.parse(rec.json));
      lastState = rec.json;
      paint();
      if (wasRunning) startLoop();
      HLJJS.api.toast('已读档：' + (rec.rom || romName) +
        (rec.rom && romName && rec.rom !== romName ? '（当前文件名 ' + romName + '）' : ''));
      return { ok: true, from: from, bytes: rec.json.length, rom: romName, savedRom: rec.rom || '' };
    } catch (e) {
      HLJJS.api.toast('读档失败：' + e.message);
      try { if (wasRunning) startLoop(); } catch (x) {}
      return { ok: false, err: e.message };
    }
  }

  /* ---------- 载入 ROM ---------- */
  function binString(u8) {
    let s = '';
    const CH = 8192;
    for (let i = 0; i < u8.length; i += CH) {
      s += String.fromCharCode.apply(null, u8.subarray(i, i + CH));
    }
    return s;
  }

  async function loadRom(file) {
    try {
      const g = HLJJS.api.game;
      ensureCanvas();
      g.setHint('正在载入…');

      const lib = await loadLib();
      const buf = new Uint8Array(await file.arrayBuffer());
      romBytes = buf;
      romKey = romKeyOf(buf);
      lastState = '';        // 换 ROM 后别把上一个游戏的内存存档带过来

      if (!lib || !lib.NES) throw new Error('库结构异常');
      nes = new lib.NES({
        onFrame,
        onAudioSample,
        onStatusUpdate: () => {},
      });
      nes.loadROM(binString(buf));
      romName = file.name.replace(/\.nes$/i, '');

      g.playing(true);
      g.setHint('');
      ensureFpsBadge();
      g.onButton((tag, down) => pressTag(tag, down));

      // 画面优先：音频失败/挂起都不影响游戏
      setTimeout(() => { try { initAudio(); } catch (e) {} }, 300);
      // 先同步跑几帧，保证第一屏不是黑的
      for (let k = 0; k < 3; k++) { try { nes.frame(); } catch (e) {} }
      paint();
      startLoop();
      try { HLJJS.api.setGameName(file.name); } catch (e) {}
      HLJJS.api.toast('已载入：' + romName);
      HLJJS.api.goto('game');
    } catch (e) {
      try { HLJJS.api.game.setHint('载入失败：' + e.message); } catch (x) {}
      HLJJS.api.toast('载入失败：' + e.message);
    }
  }

  function pick() {
    const inp = document.createElement('input');
    inp.type = 'file';
    inp.accept = '.nes,.NES,application/octet-stream';
    inp.onchange = () => {
      const f = inp.files && inp.files[0];
      if (f) loadRom(f);
    };
    inp.click();
  }

  /* ---------- 注册 ---------- */
  function integrate() {
    if (!window.HLJJS || !HLJJS.api || !HLJJS.api.game) return false;

    const opt = { label: 'NES', title: '载入红白机 ROM', onClick: pick };
    if (HLJJS.api.addGameButton) HLJJS.api.addGameButton(opt);
    else HLJJS.api.addToolbarButton(opt);

    const skipOpt = {
      label: '跳帧',
      title: '自动跳帧开关（卡顿时自动少画几帧保速度）',
      onClick() {
        autoSkip = !autoSkip;
        if (!autoSkip) skip = 0;
        HLJJS.api.toast(autoSkip ? '自动跳帧：开' : '自动跳帧：关');
        if (fpsEl) fpsEl.textContent = autoSkip ? '自动' : '不跳';
      },
    };
    if (HLJJS.api.addGameButton) HLJJS.api.addGameButton(skipOpt);

    if (HLJJS.api.addGameButton) {
      HLJJS.api.addGameButton({ label: '存档', title: '把当前进度存在本机（每个游戏一份）', onClick: saveState });
      HLJJS.api.addGameButton({ label: '读档', title: '回到上次存档的位置', onClick: loadState });
    }

    document.addEventListener('keydown', e => onKey(e, true));
    document.addEventListener('keyup', e => onKey(e, false));

    HLJJS.on('app:ready', () => {
      try { HLJJS.api.game.setHint('点「NES」载入红白机 ROM'); } catch (e) {}
    });

    HLJJS.register({
      id: 'nes',
      name: 'NES 模拟器（jsNES）',
      version: '1.0',
      onLoad() {},
    });
    return true;
  }

  // 独立模式：暴露给 standalone 页
  async function loadUrl(url, name){
    const r = await fetch(url);
    const b = await r.blob();
    const f = new File([b], name || 'game.nes');
    return loadRom(f);
  }

  /** 联机收 ROM 后先存着，等房主说开始才真正开机 */
  function prepareRom(u8, name){
    romBytes = u8;
    romKey = romKeyOf(u8);
    lastState = '';
    romName = String(name || 'net-game.nes').replace(/\.nes$/i, '');
    try { loadLib().catch(function () {}); } catch (e) {}
    try {
      const g = HLJJS.api.game;
      ensureCanvas();
      g.playing(true);
      g.setHint('ROM 已就绪，等房主开始…');
    } catch (e) {}
  }

  function romInfo(){
    if (!romBytes || !romBytes.length) return null;
    return { name: (romName || 'game') + '.nes', size: romBytes.length, bytes: romBytes };
  }

  window.NES_EMU = {
    loadRom, loadUrl, pick, stopLoop, startLoop, press: pressTag,
    prepareRom: prepareRom, romInfo: romInfo,
    saveState: saveState, loadState: loadState,
    startNet: startNet, netInput: netInput, netSum: netSum, armNet: armNet, stopNet: stopNet,
    state: () => ({ running, romName, net: NET.on, netFrame: NET.frame, netDelay: NET.delay,
                    desync: NET.desync, armed: NET.armed }),
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', integrate);
  else integrate();
})();
