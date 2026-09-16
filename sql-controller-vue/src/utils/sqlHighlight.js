/* SQL 关键字高亮：与主页面 SqlEditor 同一套样式
   关键字 / 字符串 / 注释 / 数字 分别用 .sql-keyword / .sql-string / .sql-comment / .sql-number 类名 */

const KEYWORDS = /\b(SELECT|FROM|WHERE|AND|OR|ORDER|BY|GROUP|LIMIT|INSERT|INTO|VALUES|UPDATE|SET|DELETE|CREATE|ALTER|DROP|TRUNCATE|AS|JOIN|LEFT|RIGHT|INNER|ON|NULL|IS|NOT|IN|LIKE|COUNT|DISTINCT|SHOW|DESC|DESCRIBE|WITH|HAVING|UNION|CASE|WHEN|THEN|ELSE|END|ASC|PRIMARY|KEY|ENGINE|DEFAULT|CHARSET|COLLATE|AUTO_INCREMENT|UNIQUE|INDEX|CONSTRAINT|REFERENCES|FOREIGN|CHECK|ADD|COLUMN|IF|EXISTS|BETWEEN|INNER|CROSS|FULL|OUTER|USING|COMMENT)\b/gi

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

export function highlightSql(text = '') {
  return esc(text)
    /* 注释 */
    .replace(/(--.*|\/\*[\s\S]*?\*\/)/g, '<span class="sql-comment">$1</span>')
    /* 单引号字符串（SQL 标准）—— 只匹配单引号，不匹配双引号/反引号，避免 css 类名中的双引号被二次匹配导致嵌套替换 */
    .replace(/'(?:''|[^'])*'/g, '<span class="sql-string">$&</span>')
    /* 关键字 */
    .replace(KEYWORDS, '<span class="sql-keyword">$1</span>')
    /* 数字 */
    .replace(/\b\d+(?:\.\d+)?\b/g, '<span class="sql-number">$&</span>')
}