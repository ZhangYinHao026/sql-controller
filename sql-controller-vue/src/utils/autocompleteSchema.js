/* 联想数据源：GET /api/autocomplete/schema 拉取 + 前端缓存
   - 懒加载（首次进入编辑器才调）
   - localStorage + 内存双重缓存，TTL 与后端（schema-cache-seconds，默认 3600s）一致
   - enabled=false / 请求失败 → 返回 { enabled:false }，编辑器降级为纯关键字联想 */

import { api } from '../api'

const CACHE_KEY = 'sql-autocomplete-schema'
const TTL_MS = 3600 * 1000

let memoryCache = null

export async function loadAutocompleteSchema(force = false) {
  /* 内存缓存 */
  if (!force && memoryCache) return memoryCache

  /* localStorage 缓存 */
  if (!force) {
    try {
      const raw = localStorage.getItem(CACHE_KEY)
      if (raw) {
        const { data, ts } = JSON.parse(raw)
        if (Date.now() - ts < TTL_MS) {
          memoryCache = data
          return data
        }
      }
    } catch { /* 忽略损坏缓存 */ }
  }

  try {
    const data = await api.autocompleteSchema()
    memoryCache = data
    try {
      localStorage.setItem(CACHE_KEY, JSON.stringify({ data, ts: Date.now() }))
    } catch { /* localStorage 不可用时忽略 */ }
    return data
  } catch (e) {
    console.warn('[autocomplete] 联想数据源加载失败，降级为纯关键字联想:', e.message)
    return { enabled: false }
  }
}

/* 手动刷新（如"测试连接"成功后主动失效缓存） */
export function clearAutocompleteCache() {
  memoryCache = null
  try { localStorage.removeItem(CACHE_KEY) } catch { /* ignore */ }
}

/* 后端 schema 结构 → CodeMirror lang-sql 的 SchemaConfig 结构
   后端: { enabled, connectionId, databaseName, tables: [{ name, columns: [{ name, type }] }] }
   lang-sql: { [表名]: [列名, ...] }
   不转换会直接导致 lang-sql 递归遍历字符串属性而栈溢出 */
export function toCM6Schema(schema) {
  const out = {}
  for (const t of schema?.tables || []) {
    out[t.name] = (t.columns || []).map(c => c.name)
  }
  return out
}
