/* 列名联想的上下文判断（纯函数，无 CodeMirror 依赖，便于单测）
   背景：@codemirror/lang-sql 只在「表名.」之后给列名，WHERE 里裸写列名时只给表名。
   这里负责两件事：
   1. 判断光标处该不该给列名（前面是 from/join 这类表位时不打扰）；
   2. 从当前语句里反查用到的表（FROM / JOIN / UPDATE / INTO）。 */

const TABLE_SLOT = /(?:^|[\s(,])(from|join|update|into|table|describe|desc)\s+[`"[]?[\w$.]*$/i
const DOTTED = /\.[`"\]]*$/

/** 光标前文本是否处于「可以写列名」的位置（表名. 交给 lang-sql 自己处理）。 */
export function isColumnPosition(before) {
  if (!before || !before.trim()) return false
  if (DOTTED.test(before)) return false
  return !TABLE_SLOT.test(before)
}

/** 从语句文本里取出被引用的表名（小写、去重、保序）；子查询/函数不入列表。 */
export function tableRefsOf(statement) {
  const text = statement || ''
  const refs = new Set()
  const add = raw => {
    const clean = String(raw || '').replace(/[`"[\]]/g, '')
    const parts = clean.split('.').filter(Boolean)
    const name = (parts[parts.length - 1] || '').toLowerCase()
    if (name && name !== 'select' && name !== 'dual') refs.add(name)
  }

  const re = /\b(?:from|join|update|into)\s+([`"[]?[\w$.]+[`"\]]?)/gi
  let m
  while ((m = re.exec(text))) add(m[1])

  // FROM a, b 这种逗号写法：上一条正则只吃到第一个，这里补齐
  const fromClause = text.match(/\bfrom\b([\s\S]*?)(?=\bwhere\b|\bgroup\b|\border\b|\bhaving\b|\blimit\b|\bunion\b|\bon\b|$)/i)
  if (fromClause) {
    for (const part of fromClause[1].split(',')) {
      const token = part.trim().match(/^[`"[]?[\w$.]+[`"\]]?/)
      if (token) add(token[0])
    }
  }
  return [...refs]
}

/** 取光标所在的那条语句（以 ; 分段）。 */
export function statementAt(text, pos) {
  const doc = text || ''
  const start = doc.lastIndexOf(';', Math.max(0, pos - 1)) + 1
  const end = doc.indexOf(';', pos)
  return doc.slice(start, end < 0 ? doc.length : end)
}

/**
 * 只取光标附近的一条语句（前后各 window 字符，按 ; 截断）。
 * 联想每次按键都会调用，不能把整篇文档 toString 一遍——长脚本会明显卡。
 */
export function statementAround(state, pos, window = 4000) {
  const start = Math.max(0, pos - window)
  const end = Math.min(state.doc.length, pos + window)
  return statementAt(state.sliceDoc(start, end), pos - start)
}

/** 光标处是否处于「写列名」的上下文：位置合适、且语句里确实有表可依。 */
export function isColumnContext(state, pos) {
  if (typeof state?.sliceDoc !== 'function' || !state.doc) return false
  const before = state.sliceDoc(Math.max(0, pos - 200), pos)
  if (!isColumnPosition(before)) return false
  return tableRefsOf(statementAround(state, pos)).length > 0
}

/** 后端 schema（tables[].columns[]）→ 小写表名索引，供联想取值。 */
export function toTableIndex(schema) {
  const index = Object.create(null)
  for (const t of schema?.tables || []) {
    if (!t?.name) continue
    index[String(t.name).toLowerCase()] = { name: t.name, columns: t.columns || [] }
  }
  return index
}
