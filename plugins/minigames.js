/**
 * 小游戏插件：贪吃蛇 + 2048
 *
 * 纯 JS 手写，无外部依赖，画面直接画在游戏模式那块画布上，
 * 用手柄（十字键 / ✕）操作。
 *
 * 用法：游戏模式 → 点「小游戏」→ 选一个
 */

(function () {
  'use strict';

  let cv = null, ctx = null, raf = 0, mode = '';
  let onPad = null;

  function ensureCanvas() {
    const g = HLJJS.api.game;
    cv = g.screen();
    if (!cv) throw new Error('拿不到画布');
    ctx = cv.getContext('2d');
    ctx.imageSmoothingEnabled = false;
  }

  function stop() {
    if (raf) { cancelAnimationFrame(raf); raf = 0; }
    mode = '';
  }

  /* ==================== 贪吃蛇 ==================== */
  const S = { cell: 20, cols: 24, rows: 13, body: [], dir: { x: 1, y: 0 }, food: null, tick: 0, speed: 130, alive: true, score: 0 };

  function snakeReset() {
    S.body = [{ x: 8, y: 6 }, { x: 7, y: 6 }, { x: 6, y: 6 }];
    S.dir = { x: 1, y: 0 };
    S.food = { x: 15, y: 6 };
    S.tick = 0; S.alive = true; S.score = 0;
  }

  function snakeStep() {
    const h = S.body[0];
    const nx = (h.x + S.dir.x + S.cols) % S.cols;
    const ny = (h.y + S.dir.y + S.rows) % S.rows;
    if (S.body.some(p => p.x === nx && p.y === ny)) { S.alive = false; return; }
    S.body.unshift({ x: nx, y: ny });
    if (nx === S.food.x && ny === S.food.y) {
      S.score++;
      S.food = { x: (Math.random() * S.cols) | 0, y: (Math.random() * S.rows) | 0 };
      if (S.speed > 60) S.speed -= 3;
    } else {
      S.body.pop();
    }
  }

  function snakeDraw() {
    const W = cv.width, H = cv.height;
    ctx.fillStyle = '#101810';
    ctx.fillRect(0, 0, W, H);
    const cw = W / S.cols, ch = H / S.rows;

    ctx.fillStyle = '#e04040';
    ctx.fillRect(S.food.x * cw + 2, S.food.y * ch + 2, cw - 4, ch - 4);

    ctx.fillStyle = '#7cf07c';
    S.body.forEach((p, i) => {
      ctx.fillStyle = i === 0 ? '#c8ffc8' : '#7cf07c';
      ctx.fillRect(p.x * cw + 1, p.y * ch + 1, cw - 2, ch - 2);
    });

    ctx.fillStyle = '#9ee89e';
    ctx.font = '12px monospace';
    ctx.fillText('分数 ' + S.score, 6, 14);
    if (!S.alive) {
      ctx.fillStyle = 'rgba(0,0,0,.65)';
      ctx.fillRect(0, 0, W, H);
      ctx.fillStyle = '#ff8080';
      ctx.font = 'bold 20px monospace';
      ctx.textAlign = 'center';
      ctx.fillText('结束 · ' + S.score + ' 分', W / 2, H / 2 - 6);
      ctx.fillStyle = '#ddd';
      ctx.font = '12px monospace';
      ctx.fillText('按 ✕ 重开', W / 2, H / 2 + 18);
      ctx.textAlign = 'left';
    }
  }

  function snakeLoop(t) {
    if (mode !== 'snake') return;
    raf = requestAnimationFrame(snakeLoop);
    if (S.alive && t - S.tick > S.speed) { S.tick = t; snakeStep(); }
    snakeDraw();
  }

  function snakeStart() {
    stop();
    ensureCanvas();
    snakeReset();
    mode = 'snake';
    HLJJS.api.game.playing(true);
    HLJJS.api.setGameName && HLJJS.api.setGameName('贪吃蛇');
    raf = requestAnimationFrame(snakeLoop);
  }

  /* ==================== 2048 ==================== */
  const G = { n: 4, grid: [], score: 0, over: false, won: false };

  function g2048Reset() {
    G.grid = Array.from({ length: 4 }, () => [0, 0, 0, 0]);
    G.score = 0; G.over = false; G.won = false;
    addTile(); addTile();
  }

  function addTile() {
    const empty = [];
    for (let y = 0; y < 4; y++) for (let x = 0; x < 4; x++) if (!G.grid[y][x]) empty.push([x, y]);
    if (!empty.length) return;
    const [x, y] = empty[(Math.random() * empty.length) | 0];
    G.grid[y][x] = Math.random() < 0.9 ? 2 : 4;
  }

  function slide(row) {
    const a = row.filter(v => v);
    for (let i = 0; i < a.length - 1; i++) {
      if (a[i] === a[i + 1]) { a[i] *= 2; G.score += a[i]; if (a[i] >= 2048) G.won = true; a.splice(i + 1, 1); }
    }
    while (a.length < 4) a.push(0);
    return a;
  }

  function move(dir) {
    if (G.over) return;
    const before = JSON.stringify(G.grid);
    if (dir === 'left') G.grid = G.grid.map(slide);
    else if (dir === 'right') G.grid = G.grid.map(r => slide(r.slice().reverse()).reverse());
    else {
      const T = G.grid[0].map((_, x) => G.grid.map(r => r[x]));
      let t = dir === 'up' ? T.map(slide) : T.map(c => slide(c.slice().reverse()).reverse());
      G.grid = t[0].map((_, y) => t.map(c => c[y]));
    }
    if (JSON.stringify(G.grid) !== before) addTile();
    if (!G.grid.some(r => r.some(v => !v))) {
      G.over = true;
    }
  }

  const TILE_COLORS = {
    2: '#eee4da', 4: '#ede0c8', 8: '#f2b179', 16: '#f59563', 32: '#f67c5f',
    64: '#f65e3b', 128: '#edcf72', 256: '#edcc61', 512: '#edc850',
    1024: '#edc53f', 2048: '#edc22e',
  };

  function g2048Draw() {
    const W = cv.width, H = cv.height;
    const pad = 6, size = Math.min(W, H) - pad * 2;
    const cell = size / 4;
    const ox = (W - size) / 2, oy = (H - size) / 2;

    ctx.fillStyle = '#bbada0';
    ctx.fillRect(0, 0, W, H);
    for (let y = 0; y < 4; y++) {
      for (let x = 0; x < 4; x++) {
        const v = G.grid[y][x];
        ctx.fillStyle = v ? (TILE_COLORS[v] || '#3c3a32') : '#cdc1b4';
        ctx.fillRect(ox + x * cell + 2, oy + y * cell + 2, cell - 4, cell - 4);
        if (v) {
          ctx.fillStyle = v <= 4 ? '#776e65' : '#f9f6f2';
          ctx.font = 'bold ' + Math.floor(cell * 0.36) + 'px monospace';
          ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
          ctx.fillText(String(v), ox + x * cell + cell / 2, oy + y * cell + cell / 2);
          ctx.textAlign = 'left'; ctx.textBaseline = 'alphabetic';
        }
      }
    }
    ctx.fillStyle = '#f9f6f2';
    ctx.font = '11px monospace';
    ctx.fillText('分数 ' + G.score, 6, H - 6);
    if (G.over || G.won) {
      ctx.fillStyle = 'rgba(0,0,0,.6)';
      ctx.fillRect(0, 0, W, H);
      ctx.fillStyle = G.won ? '#ffd700' : '#ff8080';
      ctx.font = 'bold 20px monospace';
      ctx.textAlign = 'center';
      ctx.fillText(G.won ? '2048！' : '无路可走', W / 2, H / 2 - 6);
      ctx.fillStyle = '#ddd'; ctx.font = '12px monospace';
      ctx.fillText('按 ✕ 重开', W / 2, H / 2 + 18);
      ctx.textAlign = 'left';
    }
  }

  function g2048Loop() {
    if (mode !== '2048') return;
    g2048Draw();
    raf = requestAnimationFrame(g2048Loop);
  }

  function g2048Start() {
    stop();
    ensureCanvas();
    g2048Reset();
    mode = '2048';
    HLJJS.api.game.playing(true);
    HLJJS.api.setGameName && HLJJS.api.setGameName('2048');
    raf = requestAnimationFrame(g2048Loop);
  }

  /* ==================== 输入 ==================== */
  function handleButton(tag, down) {
    if (!down || !mode) return;
    if (mode === 'snake') {
      if (!S.alive) { if (tag === 'cross') snakeReset(); return; }
      if (tag === 'up' && S.dir.y === 0) S.dir = { x: 0, y: -1 };
      if (tag === 'down' && S.dir.y === 0) S.dir = { x: 0, y: 1 };
      if (tag === 'left' && S.dir.x === 0) S.dir = { x: -1, y: 0 };
      if (tag === 'right' && S.dir.x === 0) S.dir = { x: 1, y: 0 };
    } else if (mode === '2048') {
      if (G.over || G.won) { if (tag === 'cross') g2048Reset(); return; }
      if (tag === 'up' || tag === 'down' || tag === 'left' || tag === 'right') move(tag);
    }
  }

  document.addEventListener('keydown', e => {
    if (!mode) return;
    const map = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', ' ': 'cross', Enter: 'cross' };
    const t = map[e.key];
    if (t) { e.preventDefault(); handleButton(t, true); }
  });

  /* ==================== 菜单 ==================== */
  function pick() {}

  function integrate() {
    if (!window.HLJJS || !HLJJS.api || !HLJJS.api.game) return;

    const g = HLJJS.api.game;
    try { g.onButton(handleButton); } catch (e) {}

    if (HLJJS.api.addGameButton) {
      HLJJS.api.addGameButton({ label: '贪吃蛇', title: '内置小游戏', onClick: snakeStart });
      HLJJS.api.addGameButton({ label: '2048', title: '内置小游戏', onClick: g2048Start });
    }

    HLJJS.register({ id: 'minigames', name: '小游戏合集', version: '1.0', onLoad() {} });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', integrate);
  else integrate();
})();
