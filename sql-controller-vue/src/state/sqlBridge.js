/**
 * 表树 → SQL 编辑器 的单向通道。
 *
 * 表树（DbTreePanel）在左侧栏里，SQL 编辑器的内容（sql ref）在 App.vue 里，
 * 两者隔了两层组件，用 props/emit 传会污染整条链路。这里放一个模块级单例：
 * 表树 push 一段 SQL，App 监听后写进编辑器并切回 SQL 工作台。
 *
 * 用 {text, at} 而不是裸字符串：连续两次「生成 SELECT」同一张表时，
 * text 相同但 at 递增，App 侧 watcher 才会再次触发。
 */
import { ref } from 'vue'

export const pendingSql = ref(null)

export function pushSql(text) {
    const value = (text || '').trim()
    if (!value) return
    pendingSql.value = { text: value, at: Date.now() }
}

/** App 消费后清空，避免热更新 / 重挂载时把旧语句又灌一次。 */
export function consumeSql() {
    pendingSql.value = null
}
