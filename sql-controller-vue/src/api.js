/**
 * 通用 fetch 封装：
 * - 默认 60s 超时（schema-snapshot 强制重抓时可显式传更长）
 * - 错误信息携带 URL + 状态码 + 原因，避免出现 "Not Found" 这种一字不着的报错
 * - 不主动吞掉 AbortError，由调用方决定是否忽略
 */
import { mergeAbortSignals } from './utils/abortSignal'

const DEFAULT_TIMEOUT_MS = 60000
// 按 SQL 类型放宽前端等待上限，与后端 sql-controller.execution/dml/ddl-timeout-seconds（300/300/600s）对齐，各留 10% 余量。
// 目的：慢 DDL（大表 ALTER ADD COLUMN 等）先等到后端给出的明确报错，而不是前端自己先掐断成"请求超时"。
// 注：query 与 dml 当前同为 300s（后端两档一致），保留两个 key 以便后续单独调整。
const SQL_TIMEOUT_MS = {query: 330000, dml: 330000, ddl: 660000}
const DDL_HEAD = new Set(['CREATE', 'ALTER', 'DROP', 'TRUNCATE', 'RENAME'])
const DML_HEAD = new Set(['INSERT', 'UPDATE', 'DELETE', 'MERGE', 'REPLACE'])

/** 取脚本中最重的一条语句决定前端超时（DDL 直接取最宽），多语句脚本不会被其中一条慢 DDL 拖垮。 */
export const timeoutForSql = sql => {
    const text = String(sql || '')
        .replace(/\/\*[\s\S]*?\*\//g, ' ')  // 去块注释，避免注释里的关键字干扰首词判断
        .replace(/--[^\n]*/g, ' ')          // 去行注释
    let ms = SQL_TIMEOUT_MS.query
    for (const stmt of text.split(';')) {
        const head = stmt.trim().split(/\s+/)[0]?.toUpperCase()
        if (!head) continue
        if (DDL_HEAD.has(head)) return SQL_TIMEOUT_MS.ddl
        if (DML_HEAD.has(head)) ms = Math.max(ms, SQL_TIMEOUT_MS.dml)
    }
    return ms
}

const request = async (path, options = {}) => {
    const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(new DOMException(`timeout after ${timeoutMs}ms`, 'TimeoutError')), timeoutMs)
    // 合并：调用方传入的 signal 触发时也走我的 ctrl，便于统一抛 "请求超时"
    // 注意：走 mergeAbortSignals 而非直接 AbortSignal.any——后者在旧内核浏览器不存在，会让每次执行 SQL 直接报错
    const signal = options.signal
        ? mergeAbortSignals([options.signal, ctrl.signal])
        : ctrl.signal
    try {
        const response = await fetch(path, {
            headers: {'Content-Type': 'application/json', ...(options.headers || {})},
            ...options,
            signal
        })
        const text = await response.text()
        let data = null
        try {
            data = text ? JSON.parse(text) : null
        } catch {
            data = text
        }
        if (!response.ok) {
            const detail = data?.detail || data?.errorMessage || data?.message || data?.error || text
            throw new Error(`[${response.status}] ${path}${detail && detail !== text ? ` · ${String(detail).slice(0, 200)}` : ''}`)
        }
        return data
    } catch (e) {
        // 仅当是我自己触发的超时（reason === 'timeout'）才归类为超时；调用方传入的 signal 取消沿用原 AbortError
        if (ctrl.signal.aborted && options.signal?.aborted) {
            throw e  // 调用方主动取消，原样抛
        }
        if (ctrl.signal.aborted) {
            throw new Error(`请求超时 (${timeoutMs}ms): ${path}`)
        }
        throw e
    } finally {
        clearTimeout(timer)
    }
}

export const api = {
    types: () => request('/api/database/types'),
    connections: (dbType, keyword) => request(`/api/database/connections?${new URLSearchParams({...(dbType ? {dbType} : {}), ...(keyword ? {keyword} : {})})}`),
    addConnection: (body) => request('/api/database/connections', {method: 'POST', body: JSON.stringify(body)}),
    updateConnection: (id, body) => request(`/api/database/connections/${id}`, {
        method: 'PUT',
        body: JSON.stringify(body)
    }),
    deleteConnection: (id) => request(`/api/database/connections/${id}`, {method: 'DELETE'}),
    testConnection: (id) => request(`/api/database/connections/${id}/test`, {method: 'POST'}),
    testDraftConnection: (body) => request('/api/database/connections/test-draft', {method: 'POST', body: JSON.stringify(body)}),
    nodes: () => request('/api/nodes'),
    addNode: (body) => request('/api/nodes', {method: 'POST', body: JSON.stringify(body)}),
    updateNode: (id, body) => request(`/api/nodes/${id}`, {method: 'PUT', body: JSON.stringify(body)}),
    deleteNode: (id) => request(`/api/nodes/${id}`, {method: 'DELETE'}),
    execute: (body, signal) => request('/api/sql/execute', {
        method: 'POST',
        body: JSON.stringify(body),
        signal,
        // 查询 60s / DML 330s / DDL 660s：与后端 JDBC setQueryTimeout 对齐，避免慢 DDL 被前端误判为超时
        timeoutMs: timeoutForSql(body?.sql)
    }),
    cancelSql: (requestId, signal) => request('/api/sql/cancel', {
        method: 'POST',
        body: JSON.stringify({requestId}),
        signal
    }),
    history: (params = {}) => request(`/api/sql/history?${new URLSearchParams(params)}`),
    historyDetail: (id) => request(`/api/sql/history/${id}`),
    convertSql: (sql, targetDbType) => request('/api/sql/convert', {
        method: 'POST',
        body: JSON.stringify({sql, targetDbType})
    }),
    autocompleteSchema: () => request('/api/autocomplete/schema'),
    schemaSnapshot: (connectionIds, force) => request('/api/diff/schema-snapshot', {
        method: 'POST',
        body: JSON.stringify({connectionIds, force: !!force}),
        // 快照为全库结构抓取：批量后 10 库 ≤ ~20s、个别库慢时留足余量；缓存命中秒回。
        // force 与 force=false 都统一 120s（缓存过期时 force=false 同样会全量重抓，不再是瞬时操作）
        timeoutMs: 120000
    }),
    // 执行补丁后的局部刷新：只抓指定表（跳过整库缓存与全库扫描），替换本地快照中这几张表
    schemaTables: (connectionId, tableNames) => request('/api/diff/schema-snapshot', {
        method: 'POST',
        body: JSON.stringify({connectionIds: [connectionId], tables: tableNames}),
        timeoutMs: 60000
    })
}
