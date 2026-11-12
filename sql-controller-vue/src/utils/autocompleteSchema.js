/* 联想数据源：GET /api/autocomplete/schema 拉取
   - 懒加载（首次进入编辑器才调）
   - 前端不留副本：后端按连接缓存（默认 24h），执行 DDL 后后端自己失效
   - force=true 才要求后端重查（手动重载用）
   - enabled=false / 请求失败 → 返回 { enabled:false }，编辑器降级为纯关键字联想 */

import { api } from '../api'

export async function loadAutocompleteSchema(force = false) {
  try {
    return await api.autocompleteSchema(force)
  } catch (e) {
    console.warn('[autocomplete] 联想数据源加载失败，降级为纯关键字联想:', e.message)
    return { enabled: false }
  }
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
