/* 安装到桌面/PWA 已删除：这个 Service Worker 只负责注销自己、清掉旧缓存。
 * 浏览器更新到它之后会 unregister，页面恢复成普通网页。 */
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    try { await self.registration.unregister(); } catch (e) {}
    try {
      const keys = await caches.keys();
      await Promise.all(keys.map(k => caches.delete(k)));
    } catch (e) {}
    try { await self.clients.claim(); } catch (e) {}
  })());
});
