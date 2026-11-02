/**
 * 数据网格的单元格渲染规则（纯函数，方便单测与复用）。
 *
 * 后端已把 JDBC 专有类型归一化成 JSON 安全值（BLOB 变成 "[BLOB 12.3 KB]" 这样的标签），
 * 这里只负责「空值、超长文本、二进制占位」的显示差异，不做二次类型转换。
 */

/** 数字列：右对齐 + 等宽数字，便于肉眼比对。 */
export function isNumericType(type) {
    const text = String(type || '').toLowerCase()
    return /(^|[\s(])(tinyint|smallint|mediumint|int|integer|bigint|decimal|numeric|number|float|double|real|serial|money)/.test(text)
}

/** 二进制/大文本列：网格里截断显示，完整内容留给后续的单元格查看器。 */
export function isBinaryType(type) {
    const text = String(type || '').toLowerCase()
    return /(blob|binary|bytea|image|clob|text|json)/.test(text)
}

/** 日期时间列：等宽显示，避免浏览器换行。 */
export function isDateType(type) {
    const text = String(type || '').toLowerCase()
    return /(date|time|timestamp)/.test(text)
}

/**
 * 单元格显示值。
 *
 * @returns {{text: string, kind: 'null'|'blob'|'number'|'text', truncated: boolean}}
 */
export function formatCell(value, type, maxLength = 240) {
    if (value === null || value === undefined) {
        return {text: 'NULL', kind: 'null', truncated: false}
    }
    let text
    if (typeof value === 'object') {
        try {
            text = JSON.stringify(value)
        } catch (e) {
            text = String(value)
        }
    } else {
        text = String(value)
    }
    if (/^\[BLOB \d/.test(text) || /^\[Binary stream\]$/.test(text)) {
        return {text, kind: 'blob', truncated: false}
    }
    const truncated = text.length > maxLength
    return {
        text: truncated ? `${text.slice(0, maxLength)}…` : text,
        kind: isNumericType(type) ? 'number' : 'text',
        truncated
    }
}

/** 悬停提示用的完整值（超长时给前 2000 字符，避免 tooltip 卡顿）。 */
export function cellTitle(value) {
    if (value === null || value === undefined) {
        return 'NULL'
    }
    const text = typeof value === 'object' ? String(value) : String(value)
    return text.length > 2000 ? `${text.slice(0, 2000)}…` : text
}

/**
 * 单元格悬停提示（带编辑语义）：NULL 与空串肉眼看不清，悬停说明「怎么把它设成什么」。
 * 普通值退化为 cellTitle。
 */
export function cellHint(value) {
    if (value === null || value === undefined) {
        return 'NULL —— 编辑时清空输入框并提交，即设回 NULL'
    }
    if (value === '') {
        return "空字符串（''）—— 编辑时输入 '' 并提交，即设为空串"
    }
    return cellTitle(value)
}

/** 行号：跨页连续。 */
export function rowNumber(page, pageSize, index) {
    const current = Math.max(1, Number(page) || 1)
    const size = Math.max(1, Number(pageSize) || 1)
    return (current - 1) * size + index + 1
}
