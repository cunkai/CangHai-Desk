/* ==========================================================================
 * 沧海 · 手机端页面装配脚本（已停用 —— 不要跑！）
 *
 * ⚠️ 本脚本会整体覆盖 app/src/main/assets/phonehome/index.html，
 *    而它的输入源（Downloads 里的设计稿）早已停用、内容远落后于产物。
 *    跑一次 = 把手机端所有后续改动（头部改版、设置弹窗、返回键逻辑……）全部抹掉。
 *    上一轮「改了 tools/phone_adapter.js 但 APK 里没生效」就是这个坑的变种。
 *
 * 现在的分工（2026-11 起）：
 *   - 页面本体：直接在 app/src/main/assets/phonehome/index.html 上改
 *   - 数据适配层：app/src/main/assets/canghai/shared/phone_adapter.js（独立资源文件）
 *     页面用 <script src="../canghai/shared/phone_adapter.js"></script> 引入，
 *     改完刷新页面即生效，不需要任何构建步骤。
 *   - tools/phone_adapter.js 只是上面那个文件的副本，改动请以 assets 里的为准。
 *
 * 本脚本仅作为「历史装配流程」留档，保留是为了说明产物是怎么拼出来的。
 * ========================================================================== */
console.error('本脚本已停用：它会覆盖 app/src/main/assets/phonehome/index.html，' +
  '而输入源（Downloads 设计稿）早已过期。请直接在产物上改，或改 assets/canghai/shared/phone_adapter.js。');
process.exit(1);

