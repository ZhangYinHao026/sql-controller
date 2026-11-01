/* ============================================================
 * 主题切换：mint（默认薄荷奶绿亮色）/ supabase（近黑 + 翡翠绿暗色）
 * 原理：<html data-theme="..."> + styles.css 里按属性覆盖 CSS 变量；
 * 选择持久化在 localStorage，index.html 头部有同 key 的内联脚本防白屏闪烁。
 * ============================================================ */
import { ref } from 'vue'

const STORAGE_KEY = 'sql-controller-theme'
export const THEMES = ['mint', 'supabase']

function readStored() {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'supabase' ? 'supabase' : 'mint'
  } catch {
    return 'mint'
  }
}

export const currentTheme = ref(readStored())

function writeDoc() {
  document.documentElement.dataset.theme = currentTheme.value
}

/* 模块加载即应用（App import 时生效，早于首帧渲染的兜底是 index.html 内联脚本） */
writeDoc()

export function applyTheme(theme) {
  currentTheme.value = THEMES.includes(theme) ? theme : 'mint'
  writeDoc()
  try {
    localStorage.setItem(STORAGE_KEY, currentTheme.value)
  } catch { /* 隐私模式等场景忽略 */ }
}

export function toggleTheme() {
  applyTheme(currentTheme.value === 'supabase' ? 'mint' : 'supabase')
}
