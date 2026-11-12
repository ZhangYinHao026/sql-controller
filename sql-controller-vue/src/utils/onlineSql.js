/**
 * 线上环境 SQL 规则（与后端 OnlineSqlGuard 同规则，仅供界面即时提示）。
 *
 * 三条硬规则（后端会再次强制，前端只负责提前告知）：
 *  1. 末尾分号会被**自动去掉**（小助手带分号必报「sql执行失败」）→ 不拦截、不报错；
 *  2. 只支持**单条语句**（多语句必须拆分）；
 *  3. 只读通道仅 SELECT/SHOW/DESC/DESCRIBE/EXPLAIN/WITH；修改仅 UPDATE/INSERT/REPLACE（DELETE/DDL 需服务端开关）。
 */

export const ONLINE_READ_HEADS = ['SELECT', 'SHOW', 'DESC', 'DESCRIBE', 'EXPLAIN', 'WITH']
export const ONLINE_WRITE_HEADS = ['UPDATE', 'INSERT', 'REPLACE']
export const ONLINE_DDL_HEADS = ['CREATE', 'ALTER', 'DROP', 'TRUNCATE', 'RENAME', 'COMMENT']

/** 去掉注释（用于判断某段是否只有注释）。 */
export function stripSqlComments(text) {
    return String(text || '')
        .replace(/\/\*[\s\S]*?\*\//g, ' ')
        .replace(/--[^\n\r]*/g, ' ')
}

/** 按分号切语句：跳过字符串/标识符/注释内的分号（与后端 SqlScriptSplitter 的简化同构）。 */
export function splitOnlineStatements(text) {
    const src = String(text || '')
    const out = []
    let cur = ''
    let i = 0
    let quote = null
    while (i < src.length) {
        const c = src[i]
        const n = src[i + 1]
        if (quote) {
            cur += c
            if (c === '\\' && i + 1 < src.length) {
                cur += src[i + 1]
                i += 2
                continue
            }
            if (c === quote) {
                if (n === quote) { // '' / "" / `` 转义
                    cur += n
                    i += 2
                    continue
                }
                quote = null
            }
            i++
            continue
        }
        if (c === '-' && n === '-') {
            while (i < src.length && src[i] !== '\n' && src[i] !== '\r') { cur += src[i]; i++ }
            continue
        }
        if (c === '/' && n === '*') {
            const end = src.indexOf('*/', i + 2)
            const stop = end < 0 ? src.length : end + 2
            cur += src.slice(i, stop)
            i = stop
            continue
        }
        if (c === "'" || c === '"' || c === '`') {
            quote = c
            cur += c
            i++
            continue
        }
        if (c === ';') {
            out.push(cur)
            cur = ''
            i++
            continue
        }
        cur += c
        i++
    }
    out.push(cur)
    return out.map(s => s.trim()).filter(s => stripSqlComments(s).trim())
}

/** 取首词（跳过前导注释/空白/括号）。 */
export function onlineHead(text) {
    let s = String(text || '')
    let guard = 0
    while (guard++ < 50) {
        const trimmed = s.replace(/^\s+/, '')
        if (trimmed.startsWith('--')) {
            const nl = trimmed.search(/[\n\r]/)
            s = nl < 0 ? '' : trimmed.slice(nl + 1)
            continue
        }
        if (trimmed.startsWith('/*')) {
            const end = trimmed.indexOf('*/')
            s = end < 0 ? '' : trimmed.slice(end + 2)
            continue
        }
        s = trimmed.replace(/^\(+/, '')
        break
    }
    const m = s.match(/^[A-Za-z_][A-Za-z0-9_$#]*/)
    return m ? m[0].toUpperCase() : ''
}

/**
 * 解析线上 SQL 的执行意图。
 * @returns {{count:number, text:string, head:string, readOnly:boolean, write:boolean, ddl:boolean,
 *            delete:boolean, hasTrailingSemicolon:boolean, empty:boolean, error:string}}
 */
export function onlineSqlInfo(sql) {
    const raw = String(sql || '')
    const statements = splitOnlineStatements(raw)
    const head = onlineHead(statements[0] || '')
    const info = {
        count: statements.length,
        text: statements[0] || '',
        head,
        readOnly: ONLINE_READ_HEADS.includes(head),
        write: ONLINE_WRITE_HEADS.includes(head),
        delete: head === 'DELETE',
        ddl: ONLINE_DDL_HEADS.includes(head),
        hasTrailingSemicolon: /;[\s;]*$/.test(raw),
        empty: statements.length === 0,
        error: ''
    }
    if (info.empty) info.error = '请输入 SQL'
    else if (info.count > 1) info.error = `线上只支持单条语句（检测到 ${info.count} 条），请拆分后逐条执行`
    return info
}

/** 展示用：去尾分号后的 SQL（仅用于提示/复制，不用于执行）。 */
export function stripTrailingSemicolon(sql) {
    return String(sql || '').replace(/;[\s;]*$/, '').trim()
}
