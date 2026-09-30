/**
 * 双层代码框（透明 textarea + 高亮 pre）的「换行对齐」工具。
 *
 * 背景：高亮层是绝对定位的 <pre>（inset:0 + overflow:hidden），真正可编辑的是盖在上面的
 * 透明 <textarea>（overflow:auto）。当 SQL 超过可视高度、textarea 右侧出现竖向滚动条后，
 * textarea 的内容区宽度会减少一个滚动条宽度，而高亮层不会跟着变窄 —— 两层换行点错开：
 * 用户看到的字（高亮层）与实际光标/选中位置（textarea）对不上，表现为「选中错乱」，
 * 同时因行数不同，滚动同步也会逐渐跑偏。
 *
 * 解法：量出滚动条实际占宽写入代码框容器的 CSS 变量 --sbw，高亮层用
 * `padding-right: calc(<左右内边距> + var(--sbw))`（或等宽透明边框）把这部分宽度让回去，
 * 使两层内容区宽度完全一致。
 */

/** textarea 右侧竖向滚动条的实际占宽（px）；未出现滚动条时为 0 */
export function measureScrollbarGutter(el) {
    if (!el || typeof getComputedStyle !== 'function') return 0
    const cs = getComputedStyle(el)
    const borders = (parseFloat(cs.borderLeftWidth) || 0) + (parseFloat(cs.borderRightWidth) || 0)
    // offsetWidth 含边框与滚动条，clientWidth 不含 —— 差值减去边框即滚动条宽度
    return Math.max(0, el.offsetWidth - el.clientWidth - borders)
}

/**
 * 把滚动条占宽同步到代码框容器（box）的 --sbw 上，供高亮层对齐。
 * @param {HTMLElement} box      代码框容器（如 .convert-code / .rp-code）
 * @param {HTMLElement} [textareaEl] 可省略，省略时从 box 内查找 textarea
 */
export function syncCodeGutter(box, textareaEl) {
    const ta = textareaEl || (box && box.querySelector && box.querySelector('textarea'))
    if (!box || !ta) return
    box.style.setProperty('--sbw', measureScrollbarGutter(ta) + 'px')
}
