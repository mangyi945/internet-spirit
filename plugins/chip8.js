/**
 * CHIP-8 模拟器插件
 *
 * CHIP-8 是 1977 年的解释型游戏平台：内存 4KB、64×32 单色屏、16 个按键、35 条指令。
 * 它足够小，可以完整且正确地实现；又足够真，跑的是当年的真游戏（Pong、太空侵略者…）。
 *
 * 两种用法：
 *   1) 独立运行：打开 plugins/standalone.html
 *   2) 嵌进主程序：丢进 plugins/ 目录即可，会自动注册
 *
 * ROM 格式：.ch8（1~3584 字节）
 */

(function () {
  'use strict';

  const W = 64, H = 32;

  const FONTSET = [
    0xF0, 0x90, 0x90, 0x90, 0xF0, // 0
    0x20, 0x60, 0x20, 0x20, 0x70, // 1
    0xF0, 0x10, 0xF0, 0x80, 0xF0, // 2
    0xF0, 0x10, 0xF0, 0x10, 0xF0, // 3
    0x90, 0x90, 0xF0, 0x10, 0x10, // 4
    0xF0, 0x80, 0xF0, 0x10, 0xF0, // 5
    0xF0, 0x80, 0xF0, 0x90, 0xF0, // 6
    0xF0, 0x10, 0x20, 0x40, 0x40, // 7
    0xF0, 0x90, 0xF0, 0x90, 0xF0, // 8
    0xF0, 0x90, 0xF0, 0x10, 0xF0, // 9
    0xF0, 0x90, 0xF0, 0x90, 0x90, // A
    0xE0, 0x90, 0xE0, 0x90, 0xE0, // B
    0xF0, 0x80, 0x80, 0x80, 0xF0, // C
    0xE0, 0x90, 0x90, 0x90, 0xE0, // D
    0xF0, 0x80, 0xF0, 0x80, 0xF0, // E
    0xF0, 0x80, 0xF0, 0x80, 0x80  // F
  ];

  // 自带演示 ROM：画一张脸然后停住
  const DEMO_ROM = [
    0x00, 0xE0,
    0xA2, 0x0A,
    0x60, 0x14,
    0x61, 0x0A,
    0xD0, 0x15,
    0x3C, 0x42, 0xA5, 0x81, 0x42,
    0x12, 0x0F
  ];

  const m = {
    mem: new Uint8Array(4096),
    V: new Uint8Array(16),
    I: 0, pc: 0x200, stack: new Uint16Array(16), sp: 0,
    delay: 0, sound: 0,
    gfx: new Uint8Array(W * H),
    keys: new Uint8Array(16),
    waitingReg: -1,
    running: false,
    romName: '',
  };

  function reset() {
    m.mem.fill(0);
    m.V.fill(0);
    m.gfx.fill(0);
    m.keys.fill(0);
    m.I = 0; m.pc = 0x200; m.sp = 0;
    m.delay = 0; m.sound = 0; m.waitingReg = -1;
    for (let i = 0; i < FONTSET.length; i++) m.mem[0x50 + i] = FONTSET[i];
  }

  function loadRom(bytes, name) {
    reset();
    const n = Math.min(bytes.length, 4096 - 0x200);
    m.mem.set(bytes.subarray(0, n), 0x200);
    m.romName = name || 'ROM';
    m.running = true;
  }

  function press(k, down) {
    if (k < 0 || k > 15) return;
    m.keys[k] = down ? 1 : 0;
    if (down && m.waitingReg >= 0) { m.V[m.waitingReg] = k; m.waitingReg = -1; }
  }

  function step() {
    if (!m.running || m.waitingReg >= 0) return;

    const op = (m.mem[m.pc] << 8) | m.mem[m.pc + 1];
    m.pc = (m.pc + 2) & 0xFFF;

    const x = (op & 0x0F00) >> 8;
    const y = (op & 0x00F0) >> 4;
    const n = op & 0x000F;
    const nn = op & 0x00FF;
    const nnn = op & 0x0FFF;

    switch (op & 0xF000) {
      case 0x0000:
        if (op === 0x00E0) { m.gfx.fill(0); }
        else if (op === 0x00EE) { m.pc = m.stack[--m.sp]; }
        break;

      case 0x1000: m.pc = nnn; break;
      case 0x2000: m.stack[m.sp++] = m.pc; m.pc = nnn; break;
      case 0x3000: if (m.V[x] === nn) m.pc += 2; break;
      case 0x4000: if (m.V[x] !== nn) m.pc += 2; break;
      case 0x5000: if (m.V[x] === m.V[y]) m.pc += 2; break;
      case 0x6000: m.V[x] = nn; break;
      case 0x7000: m.V[x] = (m.V[x] + nn) & 0xFF; break;

      case 0x8000:
        switch (n) {
          case 0x0: m.V[x] = m.V[y]; break;
          case 0x1: m.V[x] |= m.V[y]; break;
          case 0x2: m.V[x] &= m.V[y]; break;
          case 0x3: m.V[x] ^= m.V[y]; break;
          case 0x4: {
            const s = m.V[x] + m.V[y];
            m.V[0xF] = s > 255 ? 1 : 0;
            m.V[x] = s & 0xFF;
            break;
          }
          case 0x5: {
            m.V[0xF] = m.V[x] >= m.V[y] ? 1 : 0;
            m.V[x] = (m.V[x] - m.V[y]) & 0xFF;
            break;
          }
          case 0x6: {
            m.V[0xF] = m.V[x] & 1;
            m.V[x] >>= 1;
            break;
          }
          case 0x7: {
            m.V[0xF] = m.V[y] >= m.V[x] ? 1 : 0;
            m.V[x] = (m.V[y] - m.V[x]) & 0xFF;
            break;
          }
          case 0xE: {
            m.V[0xF] = (m.V[x] >> 7) & 1;
            m.V[x] = (m.V[x] << 1) & 0xFF;
            break;
          }
        }
        break;

      case 0x9000: if (m.V[x] !== m.V[y]) m.pc += 2; break;
      case 0xA000: m.I = nnn; break;
      case 0xB000: m.pc = (nnn + m.V[0]) & 0xFFF; break;
      case 0xC000: m.V[x] = ((Math.random() * 256) | 0) & nn; break;

      case 0xD000: {
        const cx = m.V[x] % W, cy = m.V[y] % H;
        m.V[0xF] = 0;
        for (let row = 0; row < n; row++) {
          const bits = m.mem[(m.I + row) & 0xFFF];
          for (let col = 0; col < 8; col++) {
            if (!(bits & (0x80 >> col))) continue;
            const px = (cx + col) % W;
            const py = (cy + row) % H;
            const idx = py * W + px;
            if (m.gfx[idx]) m.V[0xF] = 1;
            m.gfx[idx] ^= 1;
          }
        }
        break;
      }

      case 0xE000:
        if (nn === 0x9E) { if (m.keys[m.V[x] & 0xF]) m.pc += 2; }
        else if (nn === 0xA1) { if (!m.keys[m.V[x] & 0xF]) m.pc += 2; }
        break;

      case 0xF000:
        switch (nn) {
          case 0x07: m.V[x] = m.delay; break;
          case 0x0A: m.waitingReg = x; break;
          case 0x15: m.delay = m.V[x]; break;
          case 0x18: m.sound = m.V[x]; break;
          case 0x1E: m.I = (m.I + m.V[x]) & 0xFFF; break;
          case 0x29: m.I = 0x50 + (m.V[x] & 0xF) * 5; break;
          case 0x33:
            m.mem[m.I] = Math.floor(m.V[x] / 100);
            m.mem[m.I + 1] = Math.floor(m.V[x] / 10) % 10;
            m.mem[m.I + 2] = m.V[x] % 10;
            break;
          case 0x55:
            for (let i = 0; i <= x; i++) m.mem[m.I + i] = m.V[i];
            break;
          case 0x65:
            for (let i = 0; i <= x; i++) m.V[i] = m.mem[m.I + i];
            break;
        }
        break;
    }
  }

  /* ---------------- 渲染 ---------------- */
  let canvas = null, ctx = null, scale = 8;

  function attach(cv) {
    canvas = cv;
    ctx = cv.getContext('2d');
    fit();
  }

  function fit() {
    if (!canvas) return;
    scale = Math.max(1, Math.floor(Math.min(canvas.width / W, canvas.height / H)));
  }

  function draw() {
    if (!ctx) return;
    ctx.fillStyle = '#0b1f0b';
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    const ox = Math.floor((canvas.width - W * scale) / 2);
    const oy = Math.floor((canvas.height - H * scale) / 2);
    ctx.fillStyle = '#7cf07c';
    for (let y = 0; y < H; y++) {
      for (let x = 0; x < W; x++) {
        if (m.gfx[y * W + x]) ctx.fillRect(ox + x * scale, oy + y * scale, scale, scale);
      }
    }
  }

  /* ---------------- 主循环 ---------------- */
  let rafId = 0, lastTick = 0, acc = 0, lastTimer = 0;

  function loop(t) {
    if (!m.running) { rafId = 0; return; }
    rafId = requestAnimationFrame(loop);

    if (!lastTick) lastTick = t;
    acc += Math.min(100, t - lastTick);
    lastTick = t;

    // 约 500Hz
    let budget = Math.floor(acc / 2);
    acc -= budget * 2;
    if (budget > 12) budget = 12;
    while (budget-- > 0) step();

    if (!lastTimer) lastTimer = t;
    if (t - lastTimer >= 1000 / 60) {
      lastTimer = t;
      if (m.delay > 0) m.delay--;
      if (m.sound > 0) { m.sound--; beep(); }
    }

    draw();
  }

  let audio = null;
  function beep() {
    try {
      if (!audio) audio = new (window.AudioContext || window.webkitAudioContext)();
      const o = audio.createOscillator(), g = audio.createGain();
      o.type = 'square';
      o.frequency.value = 440;
      g.gain.value = 0.02;
      o.connect(g); g.connect(audio.destination);
      o.start(); o.stop(audio.currentTime + 0.03);
    } catch (e) {}
  }

  function start() {
    if (!m.running) m.running = true;
    if (!rafId) { lastTick = 0; acc = 0; rafId = requestAnimationFrame(loop); }
  }

  function stop() { m.running = false; }

  /* ---------------- 对外接口 ---------------- */
  const API = {
    load(bytes, name) { loadRom(bytes, name); start(); },
    loadDemo() { loadRom(Uint8Array.from(DEMO_ROM), '演示'); start(); },
    attach, start, stop,
    press, reset,
    state: m,
    romName: () => m.romName,
  };

  window.CHIP8 = API;

  /* ---------------- 主程序集成 ---------------- */
  function keyFromTag(tag) {
    switch (tag) {
      case 'up': return 0x2;
      case 'down': return 0x8;
      case 'left': return 0x4;
      case 'right': return 0x6;
      case 'cross': return 0x5;
      case 'circle': return 0x0;
      case 'square': return 0x1;
      case 'triangle': return 0x3;
      case 'start': return 0xD;
      case 'select': return 0xC;
      default: return -1;
    }
  }

  function integrate() {
    if (!window.HLJJS || !HLJJS.api || !HLJJS.api.game) return false;

    const g = HLJJS.api.game;
    let active = false;

    function activate(file) {
      g.playing(true);
      g.setHint('');
      const cv = g.screen();
      if (cv) attach(cv);
      g.onButton((tag) => { const k = keyFromTag(tag); if (k >= 0) press(k, true); });
      if (file) {
        file.arrayBuffer().then(buf => {
          loadRom(new Uint8Array(buf), file.name);
          try { HLJJS.api.setGameName(file.name); } catch (e) {}
          start();
        });
      } else {
        API.loadDemo();
      }
      active = true;
      HLJJS.api.goto('game');
    }

    function pick() {
      const inp = document.createElement('input');
      inp.type = 'file';
      inp.accept = '.ch8,.rom,application/octet-stream';
      inp.onchange = () => {
        const f = inp.files && inp.files[0];
        if (f) activate(f);
      };
      inp.click();
    }

    const btnOpt = { label: 'CHIP-8', title: '载入 CHIP-8 游戏', onClick: pick };
    if (HLJJS.api.addGameButton) HLJJS.api.addGameButton(btnOpt);
    else HLJJS.api.addToolbarButton(btnOpt);

    HLJJS.register({
      id: 'chip8',
      name: 'CHIP-8 模拟器',
      version: '1.0',
      onLoad() {
        HLJJS.on('app:ready', () => {
          const g2 = HLJJS.api.game;
          if (g2 && g2.setHint) g2.setHint('点上方 CHIP-8 按钮载入游戏');
        });
      },
    });

    // 键盘（电脑上）
    document.addEventListener('keydown', e => {
      if (!active) return;
      const k = keyOf(e.key);
      if (k >= 0) { press(k, true); e.preventDefault(); }
    });
    document.addEventListener('keyup', e => {
      if (!active) return;
      const k = keyOf(e.key);
      if (k >= 0) { press(k, false); e.preventDefault(); }
    });
    return true;
  }

  const KEYS = {
    '1': 0x1, '2': 0x2, '3': 0x3, '4': 0xC,
    q: 0x4, w: 0x5, e: 0x6, r: 0xD,
    a: 0x7, s: 0x8, d: 0x9, f: 0xE,
    z: 0xA, x: 0x0, c: 0xB, v: 0xF,
  };
  function keyOf(k) {
    const v = KEYS[String(k).toLowerCase()];
    return v === undefined ? -1 : v;
  }

  // 独立模式下暴露键盘映射
  window.CHIP8_keyOf = keyOf;

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', integrate);
  } else {
    integrate();
  }
})();
