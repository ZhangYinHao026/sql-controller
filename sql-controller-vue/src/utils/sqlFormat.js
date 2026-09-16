/* 轻量 SQL 格式化器：关键字大写、子句换行、字段同行、
   支持多语句（分号或语句起始关键字切分，不写分号也能识别）、字符串/注释/参数占位符保留；
   幂等：重复格式化不产生额外空行。
   options.gapStatements（默认 true）：
     true  —— 每个分号后插空行 + 语句起始关键字前插空行（主编辑器格式化原行为）；
     false —— 只断行不插空行，保留文本已有空行（供 SQL 转换结果使用：同一源语句
              拆出的 ALTER + COMMENT ON 等必须紧凑相邻，块间空行由调用方 join 提供） */

const KEYWORDS = new Set([
  'SELECT', 'FROM', 'WHERE', 'AND', 'OR', 'NOT', 'IN', 'IS', 'NULL', 'LIKE', 'BETWEEN', 'EXISTS',
  'JOIN', 'LEFT', 'RIGHT', 'INNER', 'OUTER', 'CROSS', 'FULL', 'ON', 'USING',
  'GROUP', 'BY', 'HAVING', 'ORDER', 'LIMIT', 'OFFSET', 'UNION', 'ALL', 'INTERSECT', 'EXCEPT',
  'INSERT', 'INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE', 'CREATE', 'ALTER', 'DROP', 'TRUNCATE',
  'AS', 'DISTINCT', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'TRUE', 'FALSE', 'ASC', 'DESC', 'WITH',
  'RETURNING', 'MERGE', 'SHOW', 'EXPLAIN', 'DESCRIBE',
  'ADD', 'COLUMN', 'IF', 'EXISTS', 'INDEX', 'KEY', 'PRIMARY', 'UNIQUE', 'DEFAULT', 'AUTO_INCREMENT'
])

/* 子句关键字（行内换行点）：长关键字在前。
 * ALTER 动作子句（ADD/MODIFY/DROP COLUMN、CONSTRAINT、INDEX）也作为断行点：
 * 单个 ALTER 里多动作逗号拼接时逐动作换行，避免整句挤成一行。 */
const CLAUSES = [
  'FULL OUTER JOIN', 'LEFT OUTER JOIN', 'RIGHT OUTER JOIN',
  'LEFT JOIN', 'RIGHT JOIN', 'INNER JOIN', 'CROSS JOIN', 'FULL JOIN',
  'GROUP BY', 'ORDER BY', 'INSERT INTO', 'DELETE FROM', 'CREATE TABLE', 'ALTER TABLE', 'DROP TABLE',
  'ADD CONSTRAINT', 'DROP CONSTRAINT', 'ADD FOREIGN KEY', 'DROP FOREIGN KEY',
  'ADD PRIMARY KEY', 'DROP PRIMARY KEY', 'ADD UNIQUE', 'ADD INDEX', 'DROP INDEX',
  'MODIFY COLUMN', 'ADD COLUMN', 'DROP COLUMN', 'RENAME COLUMN', 'CHANGE COLUMN',
  'UNION ALL', 'UNION', 'INTERSECT', 'EXCEPT',
  'SELECT', 'FROM', 'WHERE', 'JOIN', 'USING', 'HAVING', 'LIMIT', 'OFFSET',
  'VALUES', 'UPDATE', 'SET', 'AND', 'OR'
]

/* 语句起始关键字：用于切分多条 SQL（不依赖分号） */
const STATEMENT_START = /^(SELECT|INSERT INTO|UPDATE|DELETE FROM|CREATE TABLE|ALTER TABLE|DROP TABLE|CREATE|ALTER|DROP|TRUNCATE|WITH|EXPLAIN|SHOW|DESCRIBE)\b/i

/* CREATE TABLE 单行整句 → 列清单逐行展开（每列一行、缩进 4 空格，行内字符串/注释已被占位，括号逗号计数安全）。
   幂等：展开后各列独立成行，行首不再是 CREATE TABLE 整句，二次调用不会重复展开。
   仅紧凑模式（gapStatements=false，转换结果展示）启用，主编辑器格式化保持原样。 */
function expandCreateTableRow(row) {
  const m = row.match(/^\s*CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\b/i)
  if (!m) return row
  const base = m[0].length - m[0].trimStart().length // 行首空白长度
  const open = row.indexOf('(', base)
  if (open < 0) return row
  // 同行内是否存在顶层（深度 1）逗号：没有说明已是多行展开形态，直接返回
  let depth = 0
  let need = false
  for (let i = open; i < row.length; i++) {
    const ch = row[i]
    if (ch === '(') depth++
    else if (ch === ')') { depth--; if (depth === 0) break }
    else if (ch === ',' && depth === 1) { need = true; break }
  }
  if (!need) return row
  // 展开：顶层逗号处断行并缩进 4 空格，闭合括号与之后内容（分号/表选项）留在末行。
  // 注意起始深度为 1：列清单起始 '(' 已包含在 out 中，须计入括号层级
  let out = row.slice(0, open + 1) + '\n    '
  depth = 1
  for (let i = open + 1; i < row.length; i++) {
    const ch = row[i]
    if (ch === '(') { depth++; out += ch }
    else if (ch === ')') {
      depth--
      if (depth === 0) { out += ch + row.slice(i + 1); return out }
      out += ch
    } else if (ch === ',' && depth === 1) {
      out += ',\n    '
      // 吞掉 ", " 归一化后逗号后紧跟的空格，避免缩进多一格
      if (row[i + 1] === ' ') i++
    } else {
      out += ch
    }
  }
  return out
}

export function formatSql(sql, opts = {}) {
  if (!sql || !sql.trim()) return sql
  const gapStatements = opts.gapStatements !== false

  /* 1. 抽取字符串 / 注释 / 反引号标识 / 参数占位符 */
  const tokens = []
  const placeholders = []
  let s = sql
    .replace(/([#$])\{([^{}\s]+)\}/g, m => { placeholders.push(m); return `__PH${placeholders.length}__` })
    .replace(/'(?:''|[^'])*'/g, m => { tokens.push(m); return `__S${tokens.length}__` })
    .replace(/--[^\r\n]*/g, m => { tokens.push(m); return `__C${tokens.length}__` })
    .replace(/\/\*[\s\S]*?\*\//g, m => { tokens.push(m); return `__C${tokens.length}__` })
    .replace(/`(?:``|[^`])*`/g, m => { tokens.push(m); return `__B${tokens.length}__` })

  /* 2. 关键字大写 */
  s = s.replace(/\b([A-Za-z_][A-Za-z0-9_]*)\b/g, (m, w) => KEYWORDS.has(w.toUpperCase()) ? w.toUpperCase() : m)

  /* 3. 运算符空格：= <> != < <= > >= */
  s = s.replace(/\s*(<=|>=|<>|!=|=|<|>)\s*/g, ' $1 ')

  /* 4. 逗号后空格（已有换行则保留：否则会把多行文本列尾逗号后的换行吞成空格，造成跨行合并） */
  s = s.replace(/,\s*/g, (m, off, str) => {
    const rest = m.slice(1)
    return /[\r\n]/.test(rest) ? m : ', '
  })

  /* 5. 分号后断句：gapStatements=true 每条后留空行；=false 只断行（已有空行原样保留，
        供转换场景让 ALTER 与紧随其 COMMENT ON 紧凑相邻，组间空行由调用方构造） */
  if (gapStatements) {
    s = s.replace(/;\s*/g, ';\n\n')
  } else {
    s = s.replace(/;(\s*)/g, (m, sp) => /[\r\n]/.test(sp) ? ';' + sp : ';\n')
  }

  /* 6. 子句关键字前换行（幂等：关键字前已是换行/行首则不重复加）。
        前边界用 (?<![\w])（等价 (?<=^|[^\w])）而非 \S/. —— \S 和 . 会把 upper_limit/asset
        这类标识符内部的 limit/set 子串误当关键字拆行，须保证关键字前不是单词字符 */
  const pattern = [...CLAUSES].sort((a, b) => b.length - a.length).map(c => c.replace(/\s+/g, '\\s+')).join('|')
  const kwRe = new RegExp(`(?<=^|[^\\w])(${pattern})\\b`, 'gmi')
  s = s.replace(kwRe, (m, kw, offset, str) => {
    const prev = str[offset - 1]
    return (prev === undefined || prev === '\n') ? kw.toUpperCase() : `\n${kw.toUpperCase()}`
  })

  /* 7. 逐行整理：行尾空白清理、空行最多 1 个（仅语句之间）、WHERE/HAVING 内 AND/OR 缩进 2 格、
        括号深度 0 时按语句关键字切分多条 SQL（无分号也能识别，同语句内不留空行）。
        保留行首缩进（CREATE TABLE 展开列行依赖缩进，二次格式化不丢） */
  const lines = s.split('\n').map(l => l.replace(/\s+$/, ''))
  const out = []
  let depth = 0
  let inCondition = false
  for (const line of lines) {
    if (!line) {
      if (out.length && out[out.length - 1] !== '') out.push('')
      continue
    }
    const up = line.trimStart().toUpperCase()
    /* ALTER TABLE 内部的动作子句（ADD/MODIFY/DROP COLUMN 等）不是独立语句，
       行首命中这些时不视为新语句边界，避免在动作间插空行 */
    const isAlterPart = /^(ADD|DROP|MODIFY|CHANGE|RENAME)\s+(COLUMN|CONSTRAINT|INDEX|KEY|PRIMARY|UNIQUE|FOREIGN|CHECK)\b/.test(up)
    /* 语句边界：括号深度 0 + 行首为语句关键字 + 已有内容 + 上一行非空 → 插入空行
       （仅 gapStatements 模式；紧凑模式空行由调用方 join 控制，避免 ALTER 与 COMMENT ON 被拆开） */
    if (gapStatements && depth === 0 && out.length && out[out.length - 1] !== '' && !isAlterPart && STATEMENT_START.test(up)) {
      out.push('')
    }
    if (/^(AND|OR)\b/.test(up)) {
      out.push(inCondition ? '  ' + line : line)
    } else {
      if (/^WHERE\b|^HAVING\b/.test(up)) inCondition = true
      else if (/^(SELECT|FROM|LEFT|RIGHT|INNER|CROSS|FULL|JOIN|GROUP|ORDER|LIMIT|OFFSET|UNION|INTERSECT|EXCEPT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|VALUES|SET)\b/.test(up)) inCondition = false
      out.push(line)
    }
    depth += (line.match(/\(/g) || []).length - (line.match(/\)/g) || []).length
  }

  /* 8. 还原字符串 / 注释 / 反引号 / 占位符 */
  let result = gapStatements
    ? out.join('\n')
    : out.join('\n').split('\n').map(expandCreateTableRow).join('\n')
  result = result.replace(/__S(\d+)__/g, (m, i) => tokens[+i - 1] ?? m)
  result = result.replace(/__C(\d+)__/g, (m, i) => tokens[+i - 1] ?? m)
  result = result.replace(/__B(\d+)__/g, (m, i) => tokens[+i - 1] ?? m)
  result = result.replace(/__PH(\d+)__/g, (m, i) => placeholders[+i - 1] ?? m)

  return result.replace(/\n{3,}/g, '\n\n').trim()
}
