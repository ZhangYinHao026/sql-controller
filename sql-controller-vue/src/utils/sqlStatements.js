/**
 * 语句拼装：把后端 /api/sql/convert 返回的 statements 数组拼成可直接执行的脚本。
 *
 * 约定（与 SQL 转换弹窗、字段对比「▶ 执行」产出保持一致）：
 *   - 去掉每句自带的尾分号（后端可能带/不带），末尾统一补一个，避免出现 `;;` 或末句无分号；
 *   - 同一条源语句（MySQL）可能被拆成多条目标语句（如 ALTER ADD COLUMN → ALTER + COMMENT ON），
 *     把紧随其后的 CREATE INDEX / COMMENT ON 并入同一逻辑块 → 块内 `;\n` 紧凑相邻，块间用空行分隔；
 *   - 空白语句一律过滤（转换失败的语句不会污染脚本）。
 *
 * @param {string[]|null|undefined} statements 转换接口返回的目标方言语句列表
 * @returns {string} 可直接下发执行的脚本；无有效语句时返回 ''
 */
export function assembleStatements(statements) {
    const list = (statements || []).map(s => (s || '').trim().replace(/;+$/, '')).filter(Boolean)
    if (!list.length) return ''
    const groups = []
    for (const s of list) {
        if (/^(CREATE INDEX|COMMENT ON)\b/i.test(s) && groups.length) {
            groups[groups.length - 1].push(s)
        } else {
            groups.push([s])
        }
    }
    return groups.map(g => g.join(';\n')).join(';\n\n') + ';'
}
