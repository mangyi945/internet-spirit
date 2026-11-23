/**
 * 示例插件 —— 演示 HLJJS 插件 API
 *
 * 插件能做什么：
 *   HLJJS.on('message:in', fn)      收到消息时通知（只读）
 *   HLJJS.on('message:out', fn)     发出消息前通知
 *   HLJJS.on('app:ready', fn)       程序就绪
 *   HLJJS.api.sendText(text)        以你的身份发一条文字
 *   HLJJS.api.activeSession()       当前会话
 *   HLJJS.api.toast(msg)            弹提示
 *   HLJJS.api.addToolbarButton(o)   在工具条加一个按钮
 *   HLJJS.api.addStyle(css)         注入 CSS
 *
 * 注意：插件和主程序同源运行，拥有完整权限。
 * 只装你自己看得懂、信得过的插件。
 */

HLJJS.register({
  id: 'hello',
  name: '示例插件',
  version: '1.0',
  author: '',

  onLoad(api) {
    // 工具栏上的「插件」按钮已移除（改到菜单栏「插件」里），磁链按钮保留
    HLJJS.on('message:in', info => {
      console.log('[hello] 收到来自', info.from, '的消息：', info.text);
    });
  },
});
