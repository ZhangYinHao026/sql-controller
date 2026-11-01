/**
 * 表数据网格的「改动 → 提交项」转换（纯函数，便于单测）。
 *
 * 网格把三类未提交改动挂在 Tab 上：
 * - edits:   { [rowKey]: { 列名: 新值 } }  —— 已有行改了哪些单元格
 * - drafts:  [{ key, values: { 列名: 值 } }] —— 新增但还没提交的行
 * - deletes: [{ key, pk }]                 —— 待删除的已有行
 *
 * 提交时统一转成后端要的 { op, pk | changes | values }，避免在执行层再拼业务判断。
 */

/** 行标识：主键值用不可见分隔符拼接。无主键返回 null（该行不可编辑）。 */
export function rowKey(row, primaryKeys) {
    if (!row || !primaryKeys || !primaryKeys.length) {
        return null
    }
    const parts = []
    for (const key of primaryKeys) {
        const value = row[key]
        if (value === null || value === undefined) {
            return null
        }
        parts.push(String(value))
    }
    return parts.join('\u0001')
}

/** 从行数据里取主键对象（只取主键列，避免把整行当 WHERE 条件）。 */
export function pickPk(row, primaryKeys) {
    const pk = {}
    if (!row || !primaryKeys) {
        return pk
    }
    for (const key of primaryKeys) {
        pk[key] = row[key] === undefined ? null : row[key]
    }
    return pk
}

/** 单行是否有未提交改动。 */
export function hasRowChanges(edits, key) {
    const row = edits && key != null ? edits[key] : null
    return !!row && Object.keys(row).length > 0
}

/** 某个单元格是否被改过。 */
export function isEdited(edits, key, column) {
    const row = edits && key != null ? edits[key] : null
    return !!row && Object.prototype.hasOwnProperty.call(row, column)
}

/** 改动条要显示的统计。 */
export function dirtySummary(state = {}) {
    const edits = state.edits || {}
    let rows = 0
    let cells = 0
    for (const key of Object.keys(edits)) {
        const count = Object.keys(edits[key] || {}).length
        if (count > 0) {
            rows += 1
            cells += count
        }
    }
    const inserts = (state.drafts || []).length
    const deletes = (state.deletes || []).length
    return {
        rows,
        cells,
        inserts,
        deletes,
        total: rows + inserts + deletes,
        description: [
            rows ? `${rows} 行更新（${cells} 处）` : '',
            inserts ? `${inserts} 行新增` : '',
            deletes ? `${deletes} 行删除` : ''
        ].filter(Boolean).join(' · ')
    }
}

/** 撤销单个单元格改动（改回原值即移除该项）。 */
export function revertCell(edits, key, column) {
    const row = edits && key != null ? edits[key] : null
    if (row && Object.prototype.hasOwnProperty.call(row, column)) {
        delete row[column]
    }
    if (row && Object.keys(row).length === 0 && edits) {
        delete edits[key]
    }
    return edits
}

/**
 * 转成后端提交项列表。
 *
 * @param {object} state  { rows, edits, drafts, deletes }
 * @param {string[]} primaryKeys
 * @returns {Array<{op: string, key: string, pk?: object, changes?: object, values?: object}>}
 */
export function buildSubmitList(state, primaryKeys) {
    const rows = state.rows || []
    const edits = state.edits || {}
    const out = []

    for (const key of Object.keys(edits)) {
        const changes = edits[key] || {}
        if (!Object.keys(changes).length) {
            continue
        }
        const row = rows.find(item => rowKey(item, primaryKeys) === key)
        if (!row) {
            // 行已不在当前页（翻页后才提交的极端情况）：跳过，避免拼出没有主键的 UPDATE
            continue
        }
        out.push({op: 'update', key, pk: pickPk(row, primaryKeys), changes: {...changes}})
    }

    for (const draft of state.drafts || []) {
        const values = {}
        for (const column of Object.keys(draft.values || {})) {
            const value = draft.values[column]
            if (value !== undefined) {
                values[column] = value
            }
        }
        out.push({op: 'insert', key: draft.key, values})
    }

    for (const item of state.deletes || []) {
        out.push({op: 'delete', key: item.key, pk: {...item.pk}})
    }
    return out
}

/** 提交项 → 人看的标题（确认框里逐条列出）。 */
export function describeOperation(item) {
    if (!item) {
        return ''
    }
    if (item.op === 'update') {
        const columns = Object.keys(item.changes || {})
        return `更新 ${columns.length} 个字段：${columns.join(', ')}`
    }
    if (item.op === 'insert') {
        const columns = Object.keys(item.values || {})
        return `新增行：${columns.join(', ') || '（空行）'}`
    }
    const keys = Object.keys(item.pk || {})
    return `删除行：${keys.map(key => `${key}=${item.pk[key]}`).join(', ')}`
}
