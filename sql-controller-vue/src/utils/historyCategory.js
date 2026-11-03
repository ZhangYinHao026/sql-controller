/**
 * 执行历史的操作分档（全部 / 新增 / 修改 / 删除）
 *
 * 历史自 2026-10-24 起只记写操作，但库里仍可能有更早的记录，
 * 且个别老记录的 sqlType 解析不出关键字（语句带前导注释时会落成 '--'、'--MYSQL'），
 * 所以 sqlType 认不出来时按 SQL 正文第一个动词兜底，避免这些记录在三个分类里都看不到。
 */

export const HISTORY_CATEGORIES = {
  insert: ['INSERT', 'REPLACE', 'CREATE', 'MERGE'],
  update: ['UPDATE', 'ALTER', 'COMMENT', 'RENAME', 'DDL'],
  delete: ['DELETE', 'DROP', 'TRUNCATE']
}

const TYPE_CATEGORY = {}
for (const [cat, types] of Object.entries(HISTORY_CATEGORIES)) {
  for (const t of types) TYPE_CATEGORY[t] = cat
}

/** 去掉 /* *\/ 与 -- / # 注释后取第一个英文单词，作为 SQL 动词 */
export function sniffSqlVerb(text) {
  const raw = String(text || '')
  const cleaned = raw
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/--[^\n\r]*/g, ' ')
    .replace(/#[^\n\r]*/g, ' ')
    .trim()
  const firstWord = (cleaned.match(/[A-Za-z]+/) || [''])[0].toUpperCase()
  if (TYPE_CATEGORY[firstWord]) return firstWord
  // 注释和语句写在同一行时，整行会被当注释吃掉 → 退一步在原文里找第一个写操作动词
  const m = raw.match(/\b(INSERT|REPLACE|CREATE|MERGE|UPDATE|ALTER|COMMENT|RENAME|DELETE|DROP|TRUNCATE)\b/i)
  return m ? m[1].toUpperCase() : (firstWord || '')
}

/** 返回 'insert' | 'update' | 'delete' | 'other' */
export function historyCategory(item) {
  const byType = TYPE_CATEGORY[String(item?.sqlType || '').toUpperCase()]
  if (byType) return byType
  return TYPE_CATEGORY[sniffSqlVerb(item?.sqlText)] || 'other'
}

/** 当前页各档计数（含 all） */
export function categoryCounts(items) {
  const c = { all: 0, insert: 0, update: 0, delete: 0 }
  for (const it of items || []) {
    c.all++
    const cat = historyCategory(it)
    if (cat === 'insert' || cat === 'update' || cat === 'delete') c[cat]++
  }
  return c
}
