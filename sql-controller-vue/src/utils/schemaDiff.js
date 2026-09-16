/**
 * 字段差异对比 · 纯引擎
 *
 * 语义与原型 index/yuanxing.html「字段差异对比 v2」保持一致（安全默认）：
 * 核对方向 = 基准库 → 比对库；只自动补「缺列(ADD)」，DROP / MODIFY / 主键差异均需人工勾选。
 * 补丁统一生成 MySQL 风格，跨方言交由后端 SQL 转换器（convertAlter）翻译后执行。
 *
 * 列对象统一为：{ name, type, pk, notNull, comment, autoIncrement, default? }
 * 纯函数，无 DOM / 无全局状态，便于单测与组件复用。
 *
 * 大小写语义：数据库对象标识符（表名/列名）按「忽略大小写」对齐 —— DM/神通未加引号的对象
 * 一律存储为大写，MySQL 侧常为小写；若按字面精确比较会把同一张表/列误判为缺失。
 * 匹配一律用 eqName()/colUnion()（折叠），展示与生成 SQL 保留各自库中的原始写法。
 */

/** HTML 转义（组件模板自动转义可不用；生成文案/纯文本展示时仍可能用到）。 */
export function esc(s) {
  return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** 标识符忽略大小写比较（null 安全：双双为 null 视为相等）。 */
export function eqName(a, b) {
  if (a == null || b == null) return a == null && b == null
  return String(a).toUpperCase() === String(b).toUpperCase()
}

/** SQL 字符串字面量（单引号转义）。 */
export function sqlStr(v) {
  return "'" + String(v).replace(/\\/g, '\\\\').replace(/'/g, "''") + "'"
}

/** 默认值字面量：数字/CURRENT_TIMESTAMP/NOW() 不引号，其余加单引号。 */
export function lit(v) {
  if (typeof v === 'number') return String(v)
  const s = String(v)
  if (/^CURRENT_TIMESTAMP(\(\d+\))?$|^NOW\(\)$/i.test(s)) return s
  return sqlStr(s)
}

/** 两库列名并集（保序：先基准后比对新增；按标识符忽略大小写去重，保留先出现的原始写法）。 */
export function colUnion(L, R) {
  const s = []
  const push = f => { if (f && !s.some(x => eqName(x, f.name))) s.push(f.name) }
  ;(L || []).forEach(push)
  ;(R || []).forEach(push)
  return s
}

/* ============================================================
 * 索引版引擎：给快照列建「大写名 → 列」Map，把高频统计/比对路径
 * 从 O(列数²)（colUnion + find + eqName 线性扫）降到 O(列数)。
 * 语义与 colUnion / statTable / stmtsFor 完全一致（保序、大小写折叠去重、先基准后比对）。
 * ============================================================ */

/** 建立列索引视图：list = 列对象附带 _u（大写名）；byUpper = 大写名 → 列（首见优先）。
 *  纯函数，不改动原始列对象引用（克隆一份加 _u，避免污染快照数据）。 */
export function indexCols(cols) {
  const list = []
  const byUpper = new Map()
  ;(cols || []).forEach(f => {
    if (!f) return
    const u = String(f.name).toUpperCase()
    if (byUpper.has(u)) return
    const o = Object.assign({}, f, { _u: u })
    byUpper.set(u, o)
    list.push(o)
  })
  return { list, byUpper }
}

/** 两库列索引并集 → 逐列元组 [{name, l, r, cs}]（保序：基准列在前，比对独有列在后）。
 *  一次扫描 O(|L|+|R|)，替代 colUnion + L.find/R.find 的多遍线性扫。 */
export function mergedColsIx(L, R) {
  const rows = []
  const seen = new Set()
  const push = (l, r) => {
    const cs = colStatus(l, r)
    rows.push({ name: (l || r).name, l, r, cs })
  }
  L.list.forEach(l => { seen.add(l._u); push(l, R.byUpper.get(l._u) || null) })
  R.list.forEach(r => { if (!seen.has(r._u)) { seen.add(r._u); push(null, r) } })
  return rows
}

/** 基于列索引的表级统计（mergedColsIx 的轻量求值，不构造行对象）。 */
export function statTableIx(L, R) {
  const a = { add: 0, drop: 0, alt: 0, pk: 0 }
  const seen = new Set()
  const acc = cs => { if (cs.kind !== 'same') a[cs.kind]++ }
  L.list.forEach(l => { seen.add(l._u); acc(colStatus(l, R.byUpper.get(l._u) || null)) })
  R.list.forEach(r => { if (!seen.has(r._u)) { seen.add(r._u); acc(colStatus(null, r)) } })
  return a
}

/** 类型归一：去空白、整型显示宽度 (INT(11) → INT)。 */
export function normType(t) {
  let s = String(t || '').toUpperCase().replace(/\s+/g, ' ').trim()
  s = s.replace(/^(BIGINT|INT|INTEGER|SMALLINT|TINYINT|MEDIUMINT)\(\d+\)$/, '$1')
  return s
}

/**
 * 逐列差异判定。
 * @param l 基准列（基准库有、比对库无 → add=需补）
 * @param r 比对列（基准库无、比对库有 → drop=多出，默认不删）
 * @returns {{kind:'same'|'add'|'drop'|'pk'|'alt', reasons:string[]}}
 */
export function colStatus(l, r) {
  if (!l && !r) return { kind: 'same', reasons: [] }
  if (l && !r) return { kind: 'add', reasons: ['比对库缺此列 → 需 ADD'] }
  if (!l && r) return { kind: 'drop', reasons: ['比对库多出此列 → 默认不删'] }
  if (!!l.pk !== !!r.pk) return { kind: 'pk', reasons: [l.pk ? '目标缺主键' : '目标含主键'] }
  const rs = []
  if (normType(l.type) !== normType(r.type)) rs.push('类型 ' + r.type + ' → ' + l.type)
  if (!!l.notNull !== !!r.notNull) rs.push(l.notNull ? '改为 NOT NULL' : '改为可空 NULL')
  const ld = l.default, rd = r.default
  if ((ld !== undefined || rd !== undefined) && (ld ?? null) !== (rd ?? null))
    rs.push('默认值 ' + (rd === undefined ? '(无)' : rd) + ' → ' + (ld === undefined ? '(无)' : ld))
  if ((l.comment !== undefined || r.comment !== undefined) && String(l.comment || '') !== String(r.comment || ''))
    rs.push('列注释不同')
  return { kind: rs.length ? 'alt' : 'same', reasons: rs }
}

/** NOT NULL 且无默认时的类型兜底值（仅影响 ADD 列生成，会随 notes 提示核对）。 */
export function guessDefault(type) {
  const t = String(type).toUpperCase()
  if (/(INT|DECIMAL|NUMERIC|FLOAT|DOUBLE|BIT|YEAR|BOOL)/.test(t)) return '0'
  if (/DATE/.test(t) && !/TIME/.test(t)) return "'1970-01-01'"
  if (/TIME/.test(t)) return "'00:00:00'"
  return "''"
}

/** 生成 MySQL 风格 ADD COLUMN 列定义（不含 'ADD COLUMN' 前缀）。 */
export function genAdd(f, notes) {
  let s = '`' + f.name + '` ' + f.type
  const isText = /TEXT|BLOB|CLOB|JSON|XML/i.test(f.type)
  const noDefault = f.default === undefined || f.default === null
  if (f.notNull) {
    if (noDefault && !isText) { const d = guessDefault(f.type); s += ' NOT NULL DEFAULT ' + d; notes.push('列 `' + f.name + '` NOT NULL 且无默认值，已按类型兜底 DEFAULT ' + d + '（请核对取值）') }
    else if (noDefault && isText) { s += ' NULL'; notes.push('列 `' + f.name + '` 为 ' + f.type + ' 且 NOT NULL，MySQL 文本列无法加默认值，已退化为可空 NULL（请人工确认）') }
    else { s += ' NOT NULL'; if (!noDefault) s += ' DEFAULT ' + lit(f.default) }
  } else if (!noDefault) s += ' DEFAULT ' + lit(f.default)
  if (f.comment) s += ' COMMENT ' + sqlStr(f.comment)
  return s
}

/** 生成 MySQL 风格 MODIFY COLUMN 列定义（保留目标已有注释，防止 MODIFY 清空）。 */
export function genMod(l, r) {
  let s = '`' + l.name + '` ' + l.type
  s += l.notNull ? ' NOT NULL' : ' NULL'
  if (l.default !== undefined && l.default !== null) s += ' DEFAULT ' + lit(l.default)
  const cmt = (l.comment !== undefined && l.comment !== '') ? l.comment : r.comment
  if (cmt) s += ' COMMENT ' + sqlStr(cmt)
  return s
}

/**
 * 缺整表直接补：按基准库列快照生成 MySQL 风格 CREATE TABLE（执行时跨方言由后端转换器翻译）。
 * 与 genAdd 语义不同点：CREATE 的表无数据行，NOT NULL 无默认值合法，无需兜底 DEFAULT。
 * @param table 表名
 * @param cols 基准库列（引擎列：{name,type,pk,notNull,comment,autoIncrement,default?}）
 * @param notes 可选，收集生成提示（目前无；保留签名便于未来扩展）
 * @returns CREATE TABLE 完整语句（列清单多行缩进；主键收集为表级 PRIMARY KEY）
 */
export function genCreateTable(table, cols, notes) {
  const defs = []
  const pks = []
  ;(cols || []).forEach(f => {
    if (!f || !f.name) return
    let s = '  `' + f.name + '` ' + (f.type || 'VARCHAR(255)')
    if (f.pk || f.autoIncrement) s += ' NOT NULL'           // 主键/自增列必须非空
    else if (f.notNull) s += ' NOT NULL'
    else s += ' NULL'
    if (f.default !== undefined && f.default !== null) s += ' DEFAULT ' + lit(f.default)
    if (f.autoIncrement) s += ' AUTO_INCREMENT'
    if (f.comment) s += ' COMMENT ' + sqlStr(f.comment)
    if (f.pk) pks.push('`' + f.name + '`')
    defs.push(s)
  })
  if (pks.length) defs.push('  PRIMARY KEY (' + pks.join(', ') + ')')
  const head = 'CREATE TABLE `' + table + '` (\n'
  const body = defs.join(',\n')
  const tail = '\n);'
  return head + body + tail
}

/** 列的展示元信息（Vue 模板用）：[{k:'type'|'pk'|'null'|'default'|'cmt', v:文本}]。 */
export function metaParts(f) {
  const p = []
  if (f.type) p.push({ k: 'type', v: f.type })
  if (f.pk) p.push({ k: 'pk', v: 'PK' })
  p.push({ k: 'null', v: f.notNull ? 'NOT NULL' : 'NULL' })
  if (f.default !== undefined && f.default !== null) p.push({ k: 'default', v: 'DEFAULT ' + lit(f.default) })
  if (f.comment) p.push({ k: 'cmt', v: '「' + f.comment + '」' })
  return p
}

/** 后端快照列(ColumnInfo) → 引擎列。default 后端未抓取 → undefined（colStatus 默认值比较优雅降级）。 */
export function toEngineCol(c) {
  if (!c) return null
  return {
    name: c.name,
    type: c.type,
    pk: !!c.primaryKey,
    notNull: c.nullable === false,
    autoIncrement: !!c.autoIncrement,
    comment: c.comment || undefined
  }
}

/**
 * 表级统计（两库均存在该表时调用；缺表分支由调用方处理）。
 * @returns {{add:number, drop:number, alt:number, pk:number}}
 */
export function statTable(L, R) {
  const a = { add: 0, drop: 0, alt: 0, pk: 0 }
  colUnion(L, R).forEach(n => {
    const cs = colStatus(L.find(f => eqName(f.name, n)), R.find(f => eqName(f.name, n)))
    if (cs.kind !== 'same') a[cs.kind]++
  })
  return a
}

/**
 * 按用户勾选生成补丁语句（MySQL 风格）。
 * @param baseCols 基准库该表列
 * @param cmpCols 比对库该表列
 * @param table 表名
 * @param choose (name, kind) => boolean 该列该动作是否勾选
 * @returns {{stmts:string[], notes:string[], adds:number, hasDrop:boolean}}
 *   stmts 为空数组时 notes 说明原因（缺整表 / 基准无此表 / 未勾选）。
 */
export function stmtsFor(baseCols, cmpCols, table, choose) {
  const L = baseCols || [], R = cmpCols || []
  const notes = [], adds = [], drops = [], mods = [], pks = [], dropNames = [], modNames = []
  if (!R.length) return { stmts: [], notes: ['比对库缺少整表 `' + table + '`：请用「SQL 转换」生成建表脚本（字段注释/索引可一并还原）'], adds: 0, hasDrop: false }
  if (!L.length) return { stmts: [], notes: ['基准库无此表，无法对齐'], adds: 0, hasDrop: false }
  colUnion(L, R).forEach(n => {
    const l = L.find(f => eqName(f.name, n)), r = R.find(f => eqName(f.name, n))
    const cs = colStatus(l, r)
    if (cs.kind === 'add' && choose(n, 'add')) adds.push('ADD COLUMN ' + genAdd(l, notes))
    else if (cs.kind === 'drop' && choose(n, 'drop')) { drops.push('DROP COLUMN `' + n + '`'); dropNames.push(n) }
    else if (cs.kind === 'alt' && choose(n, 'mod')) { mods.push('MODIFY COLUMN ' + genMod(l, r)); modNames.push(n) }
    else if (cs.kind === 'pk') pks.push(n)
  })
  const parts = [...adds, ...drops, ...mods]
  const stmts = parts.length ? ['ALTER TABLE `' + table + '` ' + parts.join(', ') + ';'] : []
  /* 汇总式风险提示：同类动作合并成 1 行（列名 ≤3 全列、>3 前 3 + 等 N 列），避免逐列刷屏。 */
  if (dropNames.length) notes.push('⚠ 将删除列 ' + nameDigest(dropNames) + '，数据不可恢复，请确认')
  if (modNames.length) notes.push('⚠ MODIFY COLUMN ' + nameDigest(modNames) + '，大表会锁表，注意错峰')
  if (pks.length) notes.push('主键差异列（不自动改，请人工评估）:' + pks.map(n => '`' + n + '`').join('、'))
  return { stmts, notes, adds: adds.length, hasDrop: drops.length > 0 }
}

/** 注释用列名摘要：≤3 列全列出，>3 列只列前 3 并标注总数（保持单行）。 */
export function nameDigest(names) {
  const q = names.map(n => '`' + n + '`')
  return q.length > 3 ? q.slice(0, 3).join('、') + ' 等 ' + q.length + ' 列' : q.join('、')
}

/**
 * 向补丁文本追加/替换：同表则替换原 block（同 owner+表名），不同表追加。
 * - 块间分隔符 `\n\n+`，与组件 patchAppend 拼装一致。
 * - 块首行（trim 后）作为幂等键（已是 `-- 【owner】← 基准 base · 表 \`<t>\`` 形式）。
 * - 同一键的多份副本一并丢弃，避免历史叠加。
 */
export function appendOrReplaceBlock(text, block) {
  const norm = s => (s || '').split(/\r?\n/)[0].trim()
  const newHead = norm(block)
  if (!newHead) return (text ? text.replace(/\s+$/, '') + '\n\n' : '') + block
  if (!text || !text.trim()) return block.replace(/\s+$/, '')
  const parts = text.split(/\n\n+/)
  const kept = parts.filter(b => norm(b) !== newHead)
  kept.push(block)
  return kept.join('\n\n').replace(/\s+$/, '')
}
