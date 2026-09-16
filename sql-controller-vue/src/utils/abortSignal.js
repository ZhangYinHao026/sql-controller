/**
 * AbortSignal 合并工具（兼容旧内核浏览器）。
 *
 * 背景：`AbortSignal.any()` 是较新的 API（Chrome 116+ / Firefox 124+ / Safari 17.4+，2023 年起），
 * 旧版 Chromium 内核（部分 360/QQ 浏览器、内嵌 WebView、老版 Electron）没有它。
 * 直接调用会抛 `AbortSignal.any is not a function`——而执行 SQL 时必然要把「用户取消信号」
 * 与「超时信号」合并，一旦这一步抛错，**每次执行 SQL 都会整体失败**。
 *
 * 这里做能力探测 + 手动转发降级，语义与原生 `AbortSignal.any` 一致：
 * 任一输入 signal 中止，返回的 signal 即中止。
 *
 * @param {Array<AbortSignal|undefined|null>} signals 待合并的 signal 列表（允许含空值）
 * @param {typeof AbortSignal} [signalImpl] 仅测试用：注入 AbortSignal 实现以覆盖降级分支
 * @returns {AbortSignal|undefined} 单个 signal 原样返回；无有效 signal 返回 undefined
 */
export function mergeAbortSignals(signals, signalImpl) {
    const list = (signals || []).filter(Boolean)
    if (!list.length) return undefined
    if (list.length === 1) return list[0]
    const AS = signalImpl !== undefined ? signalImpl : (typeof AbortSignal !== 'undefined' ? AbortSignal : null)
    if (AS && typeof AS.any === 'function') return AS.any(list)
    // 降级：无原生 any → 手动转发。旧浏览器 abort(reason) 会忽略入参，无副作用。
    const ctrl = new AbortController()
    for (const s of list) {
        if (s.aborted) {
            ctrl.abort(s.reason)
            return ctrl.signal
        }
    }
    for (const s of list) s.addEventListener('abort', () => ctrl.abort(s.reason), {once: true})
    return ctrl.signal
}
