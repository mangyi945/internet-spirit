/**
 * PSP 模拟器插件
 * 用 EmulatorJS + PPSSPP 内核（WASM），在游戏模式里打开一个独立窗口。
 * 需要页面处于跨源隔离状态（COOP/COEP），否则 SharedArrayBuffer 不可用。
 */
(function () {
  'use strict';
  function integrate() {
    if (!window.HLJJS || !HLJJS.api) return;
    if (!HLJJS.api.addGameButton) return;
    HLJJS.api.addGameButton({
      label: 'PSP',
      title: 'PSP 模拟器（选 iso/cso/pbp）',
      onClick: function () {
        if (typeof HLJJS.api.openPsp === 'function') HLJJS.api.openPsp();
        else if (HLJJS.api.toast) HLJJS.api.toast('这个版本还没接 PSP 模拟器');
      }
    });
    HLJJS.register({ id: 'ppsspp', name: 'PSP 模拟器', version: '1.0', onLoad: function () {} });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', integrate);
  else integrate();
})();
