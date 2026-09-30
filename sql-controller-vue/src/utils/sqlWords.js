/* 静态词表 + 联想过滤
   CM6 的 @codemirror/lang-sql 内置词表是「各方言并集」——MySQL 方言 = SQLKeywords(264) + MySQLKeywords(220)
   ≈ 485 个关键字，其中混着 DB2 的 current_default_transform_group、MySQL 的 second_microsecond、
   SQL 标准的 search / section 等几乎不会用到的词，联想列表很噪。
   这里用白名单把联想收敛到日常会写的词：
   - 只作用于「联想下拉」，不影响语法高亮与解析（编辑器方言仍是完整 MySQL，解析树/别名/列联想不受影响）
   - 想加/减词直接改 SQL_KEYWORD_WHITELIST（小写）

   另：BUSINESS_WORDS 为公司业务补充词，两块合起来构成编辑器的全部联想来源 */

/* ===== 1. 关键字白名单 ===== */
export const SQL_KEYWORD_WHITELIST = [
  /* 查询 */
  'select', 'from', 'where', 'group', 'having', 'order', 'by', 'limit', 'offset',
  'distinct', 'all', 'as', 'union', 'with', 'recursive',
  'and', 'or', 'not', 'is', 'in', 'exists', 'between', 'like', 'regexp', 'escape',
  'null', 'true', 'false', 'asc', 'desc', 'case', 'when', 'then', 'else', 'end',
  'cast', 'convert', 'interval',
  /* 时间间隔单位（INTERVAL 1 DAY / SECOND() 等） */
  'year', 'quarter', 'month', 'week', 'day', 'hour', 'minute', 'second', 'microsecond',
  /* 连接 */
  'join', 'inner', 'left', 'right', 'full', 'outer', 'cross', 'natural', 'using', 'on',
  /* 窗口函数（MySQL 8） */
  'over', 'window', 'partition', 'rows', 'range', 'unbounded', 'preceding', 'following',
  /* 写操作 */
  'insert', 'into', 'values', 'value', 'update', 'set', 'delete', 'truncate', 'replace',
  'ignore', 'duplicate',
  /* DDL */
  'create', 'alter', 'drop', 'rename', 'table', 'view', 'index', 'database', 'schema',
  'column', 'add', 'modify', 'change', 'constraint', 'primary', 'key', 'unique',
  'foreign', 'references', 'check', 'default', 'comment', 'auto_increment',
  'engine', 'charset', 'collate', 'temporary', 'if', 'unsigned',
  'after', 'first', 'for', 'cascade', 'function', 'procedure', 'trigger',
  /* 会话 / 事务 / 运维 */
  'show', 'describe', 'explain', 'use', 'begin', 'start', 'transaction',
  'commit', 'rollback', 'savepoint', 'lock', 'unlock', 'status'
]

const KEYWORD_SET = new Set(SQL_KEYWORD_WHITELIST.map(w => String(w).trim().toLowerCase()))

/* 关键字是否保留（非关键字一律保留：表/列/函数/类型都不动） */
export function isAllowedOption(option) {
  if (option.type !== 'keyword') return true
  return KEYWORD_SET.has(String(option.label || '').toLowerCase())
}

/* ===== 2. 业务补充词 ===== */
export const BUSINESS_WORDS = [
  /* 业务字段规范缩写 */
  { label: 'create_by', detail: '创建人', type: 'keyword' },
  { label: 'create_time', detail: '创建时间', type: 'keyword' },
  { label: 'update_time', detail: '更新时间', type: 'keyword' },
  { label: 'delete_flag', detail: '删除标记', type: 'keyword' }
]

/* 自定义补全源：CM6 CompletionSource，前缀匹配业务词 */
export function businessCompletionSource(context) {
  const word = context.matchBefore(/\w*/)
  if (!word || (word.from === word.to && !context.explicit)) return null
  const prefix = word.text.toLowerCase()
  const options = BUSINESS_WORDS
    .filter(w => w.label.toLowerCase().startsWith(prefix))
    .map(w => ({ label: w.label, detail: w.detail, type: w.type }))
  return options.length ? { from: word.from, options } : null
}

/* ===== 3. 白名单过滤补全源 =====
   直接调语言自己注册的补全源（表/列/函数/关键字），只把白名单外的关键字丢掉。
   给 autocompletion({ override: [...] }) 用 —— override 会屏蔽语言自带的源，
   所以必须在这里把它们捞出来，否则表名/列名联想会一起消失。 */
const sourceCache = new WeakMap()

function asSource(src) {
  if (!Array.isArray(src)) return src
  /* 语言数据里也可能是「补全列表数组」，与 CM6 内核同样转成 source */
  let cached = sourceCache.get(src)
  if (!cached) {
    cached = context => {
      const word = context.matchBefore(/[\w$]*/)
      if (!word || (word.from === word.to && !context.explicit)) return null
      return { from: word.from, options: src }
    }
    sourceCache.set(src, cached)
  }
  return cached
}

export function keywordFilteredCompletionSource(context) {
  const registered = context.state.languageDataAt('autocomplete', context.pos)
  const results = []
  for (const raw of registered) {
    const src = asSource(raw)
    if (typeof src !== 'function') continue
    let value
    try {
      value = src(context)
    } catch {
      continue /* 单个源异常不影响其它源 */
    }
    if (!value) continue
    /* 源可能返回单个结果，也可能返回结果数组，先统一摊平 */
    for (const result of Array.isArray(value) ? value : [value]) {
      const options = (result.options || []).filter(isAllowedOption)
      if (options.length) results.push({ ...result, options })
    }
  }
  if (!results.length) return null

  /* ⚠️ 必须合成「单个 CompletionResult」返回。
     CM6 执行源走的是异步路径（startQuery → Promise.resolve(source(ctx))），
     回来后直接把返回值当单个结果用：query.done.from / query.done.options。
     返回结果数组会让内核抛 "result.options is not iterable"，联想整个打不开。
     validFor 只在「各源一致」时保留（lang-sql 两个源都是 Span，用于避免每次按键重查；
     不一致时宁可不给——内核会按需重查，行为仍正确）。 */
  let from = Infinity
  let to
  const validFors = []
  const options = []
  for (const r of results) {
    if (typeof r.from === 'number') from = Math.min(from, r.from)
    if (typeof r.to === 'number') to = to === undefined ? r.to : Math.max(to, r.to)
    if (r.validFor) validFors.push(r.validFor)
    options.push(...r.options)
  }
  const merged = { from: Number.isFinite(from) ? from : context.pos, options }
  if (to !== undefined) merged.to = to
  if (validFors.length && validFors.every(v => String(v) === String(validFors[0]))) merged.validFor = validFors[0]
  return merged
}
