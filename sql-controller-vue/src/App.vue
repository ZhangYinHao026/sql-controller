<script setup>
import {computed, onMounted, reactive, ref} from 'vue'
import {api} from './api'
import SqlEditor from './components/SqlEditor.vue'
import ConfirmDialog from './components/ConfirmDialog.vue'
import ConnectionSettings from './components/ConnectionSettings.vue'
import ConnectionFormDialog from './components/ConnectionFormDialog.vue'
import HistoryDialog from './components/HistoryDialog.vue'
import CommandSidebar from './components/CommandSidebar.vue'
import { DB_REGISTRY, typeLabel } from './utils/dbRegistry'
import { assembleStatements } from './utils/sqlStatements'
import ConvertDialog from './components/ConvertDialog.vue'
import SchemaDiffDialog from './components/SchemaDiffDialog.vue'

const sql = ref('')
const dbTypes = ref([]), connections = ref([]), selectedType = ref('mysql'), selectedIds = ref([])
const results = ref([]), logs = ref([]), error = ref(''), activeTab = ref('result'), page = ref(1), pageSize = ref(10),
    pagination = ref(true), transactional = ref(false), loading = ref(false), notice = ref('')
// 执行耗时与取消状态（取消=中止数据库端查询，非仅停前端等待）
const elapsed = ref(0), cancelling = ref(false)
let timerHandle = null
let currentAbort = null
let currentRequestId = null
const confirm = reactive({show: false, sql: '', targets: [], action: null, warnings: []})
// batch：上次执行是否为「一键转换并执行」的全类型批量（各库方言不同，全局翻页无统一 SQL）
const lastExecution = reactive({sql: '', mode: 'ALL', confirmed: false, scriptNodeId: null, params: {}, batch: false})
const cardPages = reactive({})
// connectionId → 该卡片最近一次执行的 SQL（批量执行时各库方言不同，卡片翻页必须按连接取各自 SQL）
const cardSql = reactive({})
const cardLoading = reactive({})
const nodes = ref([]), nodesLoading = ref(false)
const dialogs = reactive({settings: false, connectionForm: false, history: false, confirmDelete: false, convert: false, diff: false}), historyItems = ref([]), historyLoading = ref(false),
    historyPage = ref(1), historyPageSize = ref(10), historyTotal = ref(0),
    settingsLoading = ref(false), settingsConnections = ref([]), editingConnection = ref(null),
    pendingDelete = ref(null)
/* 类型 chips 名称/顺序：统一从 dbRegistry 派生（新增类型只改注册表） */
const typeNames = Object.fromEntries(Object.entries(DB_REGISTRY).map(([k, m]) => [k, m.label]))
const currentConnections = computed(() => connections.value.filter(item => item.dbType === selectedType.value))
const selectedConnections = computed(() => connections.value.filter(item => selectedIds.value.includes(item.id)))
const metrics = computed(() => results.value.length ? `${results.value.filter(x => x.status === 'SUCCESS').length}/${results.value.length} 个数据库成功` : '尚未执行 SQL')
const totalRows = computed(() => Math.max(0, ...results.value.map(item => Number(item.total || 0))))
const totalPages = computed(() => pagination.value ? Math.max(1, Math.ceil(totalRows.value / Math.max(1, Number(pageSize.value) || 1))) : 1)
const historyTotalPages = computed(() => Math.max(1, Math.ceil(historyTotal.value / Math.max(1, Number(historyPageSize.value) || 1))))
const historyHasNext = computed(() => historyTotal.value < 0 ? historyItems.value.length >= Number(historyPageSize.value) : historyPage.value < historyTotalPages.value)
const isRisky = value => /\b(insert|update|delete|drop|alter|truncate|create)\b/i.test(value)
const isQuery = value => /^\s*(select|show|desc|describe|with)\b/i.test(value)
let requestSequence = 0

function requestId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  requestSequence = (requestSequence + 1) % 1000000
  return `sql-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}-${requestSequence}`
}

function toast(message) {
  notice.value = message;
  setTimeout(() => {
    notice.value = ''
  }, 3200)
}

function startTimer() {
  stopTimer()
  elapsed.value = 0
  timerHandle = setInterval(() => { elapsed.value += 1 }, 1000)
}

function stopTimer() {
  if (timerHandle) {
    clearInterval(timerHandle)
    timerHandle = null
  }
}

/* 取消执行：先请求后端中止数据库端查询（跨线程 statement.cancel），再断开前端等待 */
async function cancelRunning() {
  const rid = currentRequestId
  if (!rid || cancelling.value) return
  cancelling.value = true
  try {
    const controller = new AbortController()
    const guard = setTimeout(() => controller.abort(), 8000) // 取消接口自身 8s 兜底
    try { await api.cancelSql(rid, controller.signal) } finally { clearTimeout(guard) }
  } catch (e) { /* 后端取消接口不可达时仅中止前端等待 */ }
  currentAbort?.abort() // 触发 execute 的 AbortError → 提示已取消
  cancelling.value = false
}

async function loadTypes() {
  try {
    dbTypes.value = await api.types() || []
  } catch (e) {
    toast(e.message)
  }
}

async function loadConnections() {
  try {
    connections.value = await api.connections() || [];
    if (!connections.value.some(x => x.dbType === selectedType.value)) selectedType.value = connections.value[0]?.dbType || 'mysql';
    selectedIds.value = []
  } catch (e) {
    toast(e.message)
  }
}

async function loadNodes() {
  nodesLoading.value = true
  try { nodes.value = await api.nodes() || [] } catch (e) { toast(`命令树加载失败：${e.message}`) } finally { nodesLoading.value = false }
}

async function createNode(node) {
  try {
    const created = await api.addNode(node)
    if (created?.id != null) nodes.value = [...nodes.value, created]
    if (created?.parentId != null) {
      const parent = nodes.value.find(item => String(item.id) === String(created.parentId))
      if (parent && !parent.isOpen) await api.updateNode(parent.id, { ...parent, isOpen: true })
    }
    await loadNodes()
    toast('命令已新增')
  } catch (e) { toast(e.message) }
}
async function updateNode(id, node, options = {}) {
  try {
    await api.updateNode(id, node)
    if (options.silent) {
      nodes.value = nodes.value.map(item => String(item.id) === String(id) ? { ...item, ...node } : item)
      return
    }
    await loadNodes()
    toast('命令已保存')
  } catch (e) { toast(e.message) }
}
async function removeNode(id) {
  try { await api.deleteNode(id); await loadNodes(); toast('命令已删除') } catch (e) { toast(e.message) }
}
function sendCommand(payload) {
  const node = payload?.node || payload
  const params = payload?.params || {}
  const text = node.sqlText || ''
  if (!text) return toast('脚本无 SQL 内容')
  // 发送到编辑器：追加（不清空当前内容），只执行该脚本本身
  const cur = sql.value.trim()
  sql.value = cur ? cur + '\n\n' + text : text
  startExecute(text, {scriptNodeId: node.id, params})
}

function selectType(type) {
  selectedType.value = type;
  selectedIds.value = [];
  results.value = []
}

function toggleConnection(id) {
  selectedIds.value = selectedIds.value.includes(id) ? selectedIds.value.filter(x => x !== id) : [...selectedIds.value, id]
}

function toggleAllConnections() {
  const ids = currentConnections.value.map(item => item.id)
  selectedIds.value = ids.length && ids.every(id => selectedIds.value.includes(id)) ? [] : ids
}

function startExecute(selectedSql, script = null) {
  const value = (selectedSql || sql.value).trim();
  if (!value) return toast('请输入 SQL');
  if (!selectedIds.value.length) return toast('至少选择一个数据库连接');
  const mode = selectedSql ? 'SELECTED' : 'ALL';
  const run = confirmed => execute(value, mode, confirmed, true, script);
  if (isRisky(value)) {
    Object.assign(confirm, {show: true, sql: value, targets: selectedConnections.value, warnings: [], action: () => run(true)})
  } else run(false)
}

// 转换→执行闭环：转换结果一键送回主链路（回填编辑器 + 切库型 + 预选该类型全部连接）
function runConverted(payload) {
  const { text, dbType, warnings } = payload || {}
  const value = (text || '').trim()
  if (!value) return toast('没有可执行的转换结果')
  // 回填编辑器（CodeMirror watch 自动同步）
  sql.value = value
  // 切换库型并清空旧勾选，然后自动预选该类型下全部连接
  selectType(dbType)
  const ids = connections.value.filter(item => item.dbType === dbType).map(item => item.id)
  selectedIds.value = ids
  if (!ids.length) {
    dialogs.convert = false
    const typeName = typeLabel(dbType)
    return toast(`当前没有「${typeName}」类型的连接，请先到连接设置中添加`)
  }
  dialogs.convert = false
  const mode = 'ALL'
  const run = confirmed => execute(value, mode, confirmed, true, null)
  if (isRisky(value)) {
    // 危险 SQL：确认框内必须能看到转换告警（被跳过的语句不在脚本里）
    Object.assign(confirm, {show: true, sql: value, targets: selectedConnections.value, warnings: warnings || [], action: () => run(true)})
  } else {
    if (warnings?.length) toast(`注意：转换中 ${warnings.length} 条语句被跳过（详见弹窗内告警）`)
    run(false)
  }
}

/* ---------------- 一键转换并执行：全类型分组转换 + 串行批量执行 ---------------- */

// 执行顺序固定按 dbRegistry 顺序（mysql → dm → oscar），未注册类型排最后，保证结果卡片顺序可预期
function orderedTypes(present) {
  const known = Object.keys(DB_REGISTRY).filter(t => present.has(t))
  const unknown = [...present].filter(t => !DB_REGISTRY[t])
  return [...known, ...unknown]
}

// 把原始 MySQL SQL 转成目标方言：返回可执行文本 + 告警；转换失败 / 转换后无有效语句一律抛错（由调用方记为跳过）
async function convertForType(rawSql, type) {
  const conv = await api.convertSql(rawSql, type)
  if (conv && conv.success === false) throw new Error(conv.error || '转换失败')
  const text = assembleStatements(conv?.statements)
  if (!text) throw new Error(`转换后无可用语句${conv?.warnings?.length ? `（${conv.warnings.length} 条被跳过）` : ''}`)
  return {text, warnings: (conv?.warnings || []).map(w => `${typeLabel(type)}：${w}`)}
}

// 「一键转换并执行」：把 MySQL 源 SQL 转成各方言后，对**全部连接按类型分组**逐一执行。
// 与「▶ 执行」的区别：忽略目标方言勾选、覆盖所有类型（含 mysql 用原始 SQL），结果回主界面结果卡片。
async function runConvertedAll(payload) {
  const rawSql = (payload?.sql || '').trim()
  if (!rawSql) return toast('请输入要转换的 SQL')
  if (!connections.value.length) return toast('当前没有任何数据库连接，请先到设置中添加')
  dialogs.convert = false      // 关弹窗：进度与结果都在主界面看
  sql.value = rawSql           // 回填编辑器，便于核对 / 复用原始 SQL
  // 1) 按 dbType 分组
  const idsByType = new Map()
  for (const conn of connections.value) {
    if (!idsByType.has(conn.dbType)) idsByType.set(conn.dbType, [])
    idsByType.get(conn.dbType).push(conn.id)
  }
  const types = orderedTypes(new Set(idsByType.keys()))
  // 2) 非 mysql 的方言组并发转换（mysql 组直接用原始 SQL，无需转换）
  const converted = new Map()
  const skipped = []
  const warnings = []
  await Promise.all(types.filter(t => t !== 'mysql' && DB_REGISTRY[t]).map(async type => {
    try {
      const {text, warnings: ws} = await convertForType(rawSql, type)
      converted.set(type, text)
      warnings.push(...ws)
    } catch (e) {
      skipped.push(`${typeLabel(type)} 组已跳过：${e.message}`)
    }
  }))
  // 3) 组装执行计划（未注册类型无转换能力 → 跳过）
  const plan = []
  for (const type of types) {
    const ids = idsByType.get(type) || []
    if (!ids.length) continue
    if (type === 'mysql') { plan.push({dbType: type, sql: rawSql, connectionIds: ids}); continue }
    const text = converted.get(type)
    if (!text) {
      if (!DB_REGISTRY[type]) skipped.push(`${typeLabel(type)} 组已跳过：未注册该类型，无法生成目标方言 SQL`)
      continue
    }
    plan.push({dbType: type, sql: text, connectionIds: ids})
  }
  if (!plan.length) return toast(`没有可执行的目标（${skipped.join('；') || '无可用连接'}）`)
  // 4) 危险 SQL 沿用现有二次确认；目标列出全部待执行库，并把转换告警一并透出
  const targets = connections.value.filter(conn => plan.some(p => p.dbType === conn.dbType))
  if (isRisky(rawSql)) {
    Object.assign(confirm, {show: true, sql: rawSql, targets, warnings, action: () => executeBatch(plan, true, skipped)})
  } else {
    executeBatch(plan, false, skipped)
  }
}

// 全类型批量执行：后端禁止单请求混选不同 dbType → 逐组串行下发，结果累加展示。
// 复用主执行链路的 loading / 计时 / 取消（currentRequestId 指向当前组，取消即中止当前组并断开前端等待）。
async function executeBatch(plan, confirmed, skipped = []) {
  loading.value = true
  error.value = skipped.join('\n')
  activeTab.value = skipped.length ? 'errors' : 'result'
  page.value = 1
  lastExecution.sql = ''
  lastExecution.mode = 'ALL'
  lastExecution.confirmed = confirmed
  lastExecution.scriptNodeId = null
  lastExecution.params = {}
  lastExecution.batch = true
  results.value = []
  Object.keys(cardPages).forEach(k => delete cardPages[k])
  Object.keys(cardSql).forEach(k => delete cardSql[k])
  // 主界面选择收敛到第一组：结果卡片按 connectionId 渲染不依赖选择，但若 selectedIds 残留混合类型，
  // 之后点编辑器「执行 SQL」会撞后端「不能混选不同数据库类型」。
  selectedType.value = plan[0].dbType
  selectedIds.value = [...plan[0].connectionIds]
  const totalCount = plan.reduce((sum, p) => sum + p.connectionIds.length, 0)
  const failures = []
  let okCount = 0
  let aborted = false
  startTimer()
  const controller = new AbortController()
  currentAbort = controller
  try {
    for (const group of plan) {
      if (controller.signal.aborted) { aborted = true; break }
      const rid = requestId()
      currentRequestId = rid
      try {
        const data = await api.execute({
          connectionIds: group.connectionIds,
          sql: group.sql,
          executionMode: 'ALL',
          page: 1,
          pageSize: pagination.value ? Number(pageSize.value) : 100,
          transactional: transactional.value,
          confirmed,
          requestId: rid
        }, controller.signal)
        const list = data?.results || []
        results.value = [...results.value, ...list]   // 累加：多组结果合并展示
        list.forEach(item => { cardPages[item.connectionId] = 1; cardSql[item.connectionId] = group.sql })
        logs.value.unshift(...list.map(item => ({
          ...item,
          sql: group.sql,
          time: new Date().toLocaleString('zh-CN', {hour12: false})
        })))
        okCount += Number(data?.successConnections || 0)
        list.filter(item => item.status === 'FAILED').forEach(item => failures.push(`${item.regionName}: ${item.errorMessage}`))
      } catch (e) {
        if (e?.name === 'AbortError') { aborted = true; break }   // 用户取消：不再下发后续组
        failures.push(`${typeLabel(group.dbType)} 组执行失败：${e.message}`)   // 单组失败不阻断其它类型
      }
    }
    if (failures.length) {
      error.value = [error.value, ...failures].filter(Boolean).join('\n')
      activeTab.value = 'errors'
    }
    toast(aborted
      ? `批量执行已取消：${okCount}/${totalCount} 个数据库成功`
      : `批量执行完成：${okCount}/${totalCount} 个数据库成功`)
    return !failures.length && !aborted
  } finally {
    stopTimer()
    currentAbort = null
    currentRequestId = null
    loading.value = false
  }
}

// 字段对比「▶ 执行到 XX」：仅向目标库连接发送（单目标库隔离语义）
// 跨方言（目标 dbType ≠ mysql）时先经 /api/sql/convert 翻译为 MySQL 风格的补丁；
// 转换告警（被跳过的语句）透出到执行确认框，防止用户误以为已包含。
// 无论成功 / 失败 / 取消，都通过 onDone({ok, rows, error, ms}) 通知 SchemaDiffDialog，
// 由其把分桶状态写成 done / fail（避免“执行中”卡死），成功后再做局部快照刷新。
let diffReport = null   // 当前字段对比执行的目标回调（全局危险确认框被取消时也要通知，防止分桶卡“执行中”）
function notifyDiff(res) {
  const fn = diffReport
  diffReport = null
  if (fn) { try { fn(res) } catch (err) { console.warn('diff onDone failed:', err) } }
}
async function runDiffScript(payload) {
  diffReport = typeof payload?.onDone === 'function' ? payload.onDone : null
  const rawSql = (payload?.sql || '').trim()
  const conn = connections.value.find(item => item.id === payload?.connectionId)
  if (!rawSql || !conn) { notifyDiff({ok: false, error: '没有可执行的同步脚本或目标库连接已删除'}); return toast('没有可执行的同步脚本或目标库连接已删除') }
  let text = rawSql
  let warnings = []
  if (conn.dbType !== 'mysql') {
    try {
      const conv = await api.convertSql(rawSql, conn.dbType)
      warnings = conv?.warnings || []
      // 拼装规则与「一键转换并执行」共用（utils/sqlStatements.js）：同源拆出的 COMMENT ON / CREATE INDEX 紧贴并入
      text = assembleStatements(conv?.statements)
      if (!text) {
        const msg = `转换后无可用语句${warnings.length ? `（${warnings.length} 条被跳过）` : ''}——请用「SQL 转换」查看原因后人工处理`
        notifyDiff({ok: false, error: msg}); return toast(msg)
      }
    } catch (e) {
      notifyDiff({ok: false, error: `跨方言转换失败：${e.message}`}); return toast(`跨方言转换失败：${e.message}`)
    }
  }
  // 回填编辑器 + 切到目标库型并只预选该连接（结果在主区正常展示）
  // 注意：不关闭字段对比面板 —— 执行后 SchemaDiffDialog 侧分桶状态就地更新（✓已执行 / ✗失败可重试），
  //       用户可继续逐库执行/对比；想看执行结果时自行关闭面板即可。
  sql.value = text
  selectType(conn.dbType)
  selectedIds.value = [conn.id]
  const mode = 'ALL'
  // execute 成功/失败都回调（字段对比据此写分桶状态 + 仅执行过的表局部快照刷新）
  const finish = async confirmed => {
    const t0 = Date.now()
    const ok = await execute(text, mode, confirmed, true, null)
    const res = { ok, ms: Date.now() - t0 }
    if (ok) {
      const hit = results.value.find(r => String(r.connectionId) === String(payload?.connectionId))
      res.rows = hit && hit.affectedRows != null ? Number(hit.affectedRows) : (hit && hit.total != null ? Number(hit.total) : null)
    } else {
      res.error = error.value || '执行失败（可能已取消）'
    }
    notifyDiff(res)
  }
  if (isRisky(text)) {
    Object.assign(confirm, {show: true, sql: text, targets: [conn], warnings, action: () => finish(true)})
  } else {
    if (warnings.length) toast(`注意：转换中 ${warnings.length} 条语句被跳过（详见弹窗内告警）`)
    finish(false)
  }
}

async function execute(value, mode, confirmed = false, resetPage = true, script = null) {
  loading.value = true;
  error.value = '';
  activeTab.value = 'result';
  if (resetPage) page.value = 1;
  lastExecution.sql = value;
  lastExecution.mode = mode;
  lastExecution.confirmed = confirmed;
  lastExecution.scriptNodeId = script?.scriptNodeId || null;
  lastExecution.params = script?.params || {};
  lastExecution.batch = false;   // 单类型执行：退出批量模式（结果共享同一条 SQL，全局翻页可用）
  const rid = requestId();
  currentRequestId = rid;
  startTimer();
  const controller = new AbortController();
  currentAbort = controller;
  try {
    const data = await api.execute({
      connectionIds: selectedIds.value,
      sql: value,
      ...(script?.scriptNodeId ? {scriptNodeId: script.scriptNodeId, params: script.params || {}} : {}),
      executionMode: mode,
      page: page.value,
      pageSize: pagination.value ? Number(pageSize.value) : 100,
      transactional: transactional.value,
      confirmed,
      requestId: rid
    }, controller.signal);
    results.value = data.results || [];
    results.value.forEach(item => { cardPages[item.connectionId] = 1; cardSql[item.connectionId] = value });
    logs.value.unshift(...results.value.map(item => ({
      ...item,
      sql: value,
      time: new Date().toLocaleString('zh-CN', {hour12: false})
    })));
    if (results.value.some(item => item.status === 'FAILED')) {
      error.value = results.value.filter(item => item.status === 'FAILED').map(item => `${item.regionName}: ${item.errorMessage}`).join('\n');
      activeTab.value = 'errors'
    }
    toast(`执行完成：${data.successConnections || 0}/${data.totalConnections || 0} 个数据库成功`)
    return !results.value.some(item => item.status === 'FAILED')
  } catch (e) {
    if (e?.name === 'AbortError') {
      toast('查询已取消'); // 用户手动取消：不展示错误
      return false
    }
    error.value = e.message;
    activeTab.value = 'errors';
    toast(e.message);
    return false
  } finally {
    stopTimer();
    currentAbort = null;
    currentRequestId = null;
    loading.value = false
  }
}

function confirmExecute() {
  confirm.show = false;
  confirm.action?.();
  confirm.action = null
}

function cancelConfirm() {
  confirm.show = false
  confirm.action = null
  notifyDiff({ok: false, error: '已取消执行确认'})
}

async function reloadResults() {
  // 批量执行（一键转换并执行）时各库方言不同、没有统一 SQL，全局翻页无意义 → 用每张卡片内的翻页
  if (lastExecution.batch) return;
  if (!lastExecution.sql || !isQuery(lastExecution.sql)) return;
  page.value = Math.min(page.value, totalPages.value);
  await execute(lastExecution.sql, lastExecution.mode, lastExecution.confirmed, false, {
    scriptNodeId: lastExecution.scriptNodeId,
    params: lastExecution.params
  })
}

async function previousPage() {
  if (loading.value || page.value <= 1) return;
  page.value -= 1;
  await reloadResults()
}

async function nextPage() {
  if (loading.value || page.value >= totalPages.value) return;
  page.value += 1;
  await reloadResults()
}

function cardPage(item) {
  return cardPages[item.connectionId] || 1
}

// 卡片翻页取该连接自己的 SQL：批量执行（一键转换并执行）时各库方言不同，不能共用 lastExecution.sql
function sqlOfCard(item) {
  return cardSql[item.connectionId] || lastExecution.sql
}

function cardTotalPages(item) {
  return pagination.value ? Math.max(1, Math.ceil(Number(item.total || 0) / Math.max(1, Number(pageSize.value) || 1))) : 1
}

async function changeCardPage(item, delta) {
  const current = cardPage(item)
  const target = current + delta
  // 取该卡片自己的 SQL：批量执行时 MySQL / DM8 / Oscar 各组 SQL 不同
  const sql = sqlOfCard(item)
  if (cardLoading[item.connectionId] || target < 1 || target > cardTotalPages(item) || !sql || !isQuery(sql)) return
  cardLoading[item.connectionId] = true
  try {
    const data = await api.execute({
      connectionIds: [item.connectionId], sql, executionMode: lastExecution.mode,
      ...(lastExecution.scriptNodeId ? {scriptNodeId: lastExecution.scriptNodeId, params: lastExecution.params} : {}),
      page: target, pageSize: pagination.value ? Number(pageSize.value) : 100,
      confirmed: lastExecution.confirmed, requestId: requestId()
    })
    const replacement = data.results?.[0]
    if (replacement) {
      const index = results.value.findIndex(row => row.connectionId === item.connectionId)
      if (index >= 0) results.value[index] = replacement
      cardPages[item.connectionId] = target
    }
  } catch (e) {
    toast(e.message)
  } finally {
    cardLoading[item.connectionId] = false
  }
}

async function openSettings() {
  dialogs.settings = true;
  settingsLoading.value = true;
  try {
    settingsConnections.value = await api.connections() || []
  } catch (e) {
    toast(e.message)
  } finally {
    settingsLoading.value = false
  }
}

function addConnection() {
  editingConnection.value = null;
  dialogs.connectionForm = true
}

function editConnection(row) {
  editingConnection.value = row;
  dialogs.connectionForm = true
}

async function saveConnection(payload) {
  try {
    if (payload.id) await api.updateConnection(payload.id, payload.body); else await api.addConnection(payload.body);
    await loadConnections();
    settingsConnections.value = await api.connections() || [];
    dialogs.connectionForm = false;
    toast('连接已保存')
  } catch (e) {
    toast(e.message)
  }
}

async function removeConnection(row) {
  pendingDelete.value = row
  dialogs.confirmDelete = true
}

async function confirmDeleteConnection() {
  const row = pendingDelete.value
  if (!row) return
  dialogs.confirmDelete = false
  pendingDelete.value = null
  try {
    await api.deleteConnection(row.id);
    selectedIds.value = selectedIds.value.filter(id => id !== row.id);
    await loadConnections();
    settingsConnections.value = await api.connections() || [];
    toast('连接已删除')
  } catch (e) {
    toast(e.message)
  }
}

async function testConnection(row) {
  try {
    const result = await api.testConnection(row.id);
    toast(result.success ? `连接成功，耗时 ${result.durationMs} ms` : result.errorMessage || '连接失败')
  } catch (e) {
    toast(e.message)
  }
}

async function testFormConnection(body) {
  try {
    const result = await api.testDraftConnection(body)
    toast(result.success ? `连接成功，耗时 ${result.durationMs} ms` : result.errorMessage || '连接失败')
  } catch (e) {
    toast(e.message)
  }
}

async function openHistory() {
  dialogs.history = true;
  await loadHistory(1)
}

async function loadHistory(targetPage) {
  if (historyLoading.value) return;
  historyLoading.value = true;
  try {
    const data = await api.history({page: targetPage, pageSize: Number(historyPageSize.value)}) || []
    if (Array.isArray(data)) {
      // 旧后端返回纯数组（无 total）：用"满页则有下一页"启发式
      historyItems.value = data;
      historyTotal.value = data.length < Number(historyPageSize.value) ? (targetPage - 1) * Number(historyPageSize.value) + data.length : -1
    } else {
      historyItems.value = data.content || [];
      historyTotal.value = Number(data.total || 0)
    }
    historyPage.value = targetPage
  } catch (e) {
    toast(e.message)
  } finally {
    historyLoading.value = false
  }
}

function changeHistoryPageSize(size) {
  historyPageSize.value = Math.max(1, Math.min(100, Number(size) || 10));
  loadHistory(1)
}

onMounted(async () => {
  await Promise.all([loadTypes(), loadConnections(), loadNodes()])
})
</script>

<template>
  <header class="topbar">
    <div class="topbar-brand">
      <img class="brand-mark" src="/icon/tabIcon.jpg" alt="" aria-hidden="true">
      <h1 class="topbar-title">SQL 工作台</h1>
    </div>
    <nav class="topbar-nav">
      <button class="theme-btn" type="button" title="主题切换（敬请期待）" aria-label="主题切换">🌙</button>
      <button class="nav-btn" @click="openHistory">📜 历史记录</button>
      <button class="nav-btn" @click="dialogs.diff = true">⇆ 字段对比</button>
      <button class="nav-btn" @click="openSettings">⚙ 设置</button>
    </nav>
  </header>
  <main class="workspace">
    <CommandSidebar :nodes="nodes" :loading="nodesLoading" @reload="loadNodes" @create="createNode" @update="updateNode" @remove="removeNode" @send="sendCommand" @error="toast" @convert="dialogs.convert = true" />
    <div class="container workspace-content">
    <section class="card selector">
      <div class="selector-row"><span class="label">数据库类型</span>
        <div class="choices">
          <button v-for="(name, type) in typeNames" :key="type" class="choice"
                  :class="{ active: selectedType === type }" @click="selectType(type)">{{ name }} <small
              v-if="dbTypes.find(item => item.dbType === type)">({{
              dbTypes.find(item => item.dbType === type).total
            }})</small></button>
        </div>
      </div>
      <div class="selector-row"><span class="label">省份 / 环境</span>
        <div class="choices">
          <button v-if="currentConnections.length >= 2" class="choice select-all" :class="{ active: currentConnections.every(item => selectedIds.includes(item.id)) }" @click="toggleAllConnections">{{ currentConnections.every(item => selectedIds.includes(item.id)) ? '取消全选' : '全选' }}</button>
          <button v-for="item in currentConnections" :key="item.id" class="choice"
                  :class="{ active: selectedIds.includes(item.id) }" @click="toggleConnection(item.id)">
            {{ item.regionName }}
          </button>
          <span v-if="!currentConnections.length" class="muted">暂无该类型连接，请在设置中新增</span></div>
        <span class="note">可多选，同一 SQL 将在每个选中数据库中执行</span></div>
    </section>
    <SqlEditor v-model="sql" v-model:transactional="transactional" @execute="startExecute"/>
    <section class="card result">
      <div class="result-head">
        <div class="tabs">
          <button class="tab" :class="{ active: activeTab === 'result' }" @click="activeTab = 'result'">查询结果
          </button>
          <button class="tab" :class="{ active: activeTab === 'logs' }" @click="activeTab = 'logs'">执行日志</button>
          <button class="tab" :class="{ active: activeTab === 'errors' }" @click="activeTab = 'errors'">错误信息
          </button>
        </div>
        <span class="metrics">{{ metrics }}</span></div>
      <div v-if="activeTab === 'result'">
        <div class="result-tools">          <label class="pagination-control">
            <button class="switch" :class="{ on: pagination }" @click="pagination = !pagination"></button>
            启用分页　每页 <input v-model.number="pageSize" class="small-input" type="number" min="1" max="100"
                                 @change="reloadResults"/> 条</label>
          <span class="muted">{{ lastExecution.batch ? '批量执行模式：各库方言不同，请使用每张卡片内的翻页' : '每个数据库结果可单独翻页' }}</span>
        </div>
        <div v-if="loading" class="empty executing-hint">
          <span>正在执行 SQL… 已耗时 <b class="elapsed">{{ elapsed }}</b> 秒</span>
          <button class="btn small" :disabled="cancelling" @click="cancelRunning">
            {{ cancelling ? '正在取消…' : '取消执行' }}
          </button>
        </div>
        <div v-else-if="!results.length" class="empty">执行 SELECT 查询后将在此显示各数据库结果</div>
        <div v-else class="result-grid">
          <article v-for="item in results" :key="item.connectionId" class="result-card">
            <div class="result-card-head"><b>{{ typeNames[item.dbType] }} · {{
                item.regionName
              }}</b><span>{{
                item.status === 'SUCCESS' ? `${item.total || item.affectedRows || 0} 条记录 · ${item.durationMs} ms` : (item.status === 'CANCELLED' ? '已取消' : '执行失败')
              }}</span></div>
            <div v-if="item.status === 'CANCELLED'" class="error-inline cancel-inline">{{ item.errorMessage || '查询已取消' }}</div>
            <div v-else-if="item.status === 'FAILED'" class="error-inline">{{ item.errorMessage }}</div>
            <div v-else-if="!item.columns?.length" class="empty small-empty">执行成功，影响 {{ item.affectedRows || 0 }}
              行
            </div>
            <div v-else class="table-wrap">
              <table class="data-table" :class="{ 'table-loading': cardLoading[item.connectionId] }">
                <thead>
                <tr>
                  <th v-for="column in item.columns" :key="column">{{ column }}</th>
                </tr>
                </thead>
                <tbody>
                <tr v-for="(row, index) in item.rows" :key="index">
                  <td v-for="column in item.columns" :key="column">{{ row[column] }}</td>
                </tr>
                </tbody>
              </table>
            </div>
            <div v-if="item.status === 'SUCCESS' && item.columns?.length" class="card-pagination">
              <button class="btn small" :disabled="!pagination || cardLoading[item.connectionId] || cardPage(item) <= 1" @click="changeCardPage(item, -1)">上一页</button>
              <span class="page-indicator">第 {{ cardPage(item) }} / {{ cardTotalPages(item) }} 页</span>
              <button class="btn small" :disabled="!pagination || cardLoading[item.connectionId] || cardPage(item) >= cardTotalPages(item)" @click="changeCardPage(item, 1)">下一页</button>
              <button class="btn small" @click="toast('导出功能将在后续版本提供')">导出</button>
            </div>
          </article>
        </div>
      </div>
      <div v-else-if="activeTab === 'logs'" class="log-list">
        <div v-if="!logs.length" class="empty">暂无执行日志</div>
        <div v-for="(log, index) in logs" :key="index" class="log"><span>{{ log.time }}</span><span>{{
            log.regionName
          }}</span><span>{{ log.status === 'SUCCESS' ? '成功' : '失败' }}</span><span class="sql-summary">{{
            log.sql
          }}</span></div>
      </div>
      <div v-else class="error-box" :class="{ visible: error }">{{ error || '暂无错误信息' }}</div>
    </section>
    </div>
  </main>
  <ConfirmDialog :show="confirm.show" :sql="confirm.sql" :targets="confirm.targets" :warnings="confirm.warnings" @confirm="confirmExecute"
                 @cancel="cancelConfirm"/>
  <ConfirmDialog mode="generic" title="删除数据库连接"
                 :message="pendingDelete ? `确定删除 “${pendingDelete.regionName}” 连接吗？此操作不可恢复。` : ''"
                 confirm-text="删除" :show="dialogs.confirmDelete"
                 @confirm="confirmDeleteConnection" @cancel="dialogs.confirmDelete = false; pendingDelete = null"/>
  <ConnectionSettings :show="dialogs.settings" :connections="settingsConnections" :loading="settingsLoading"
                      @close="dialogs.settings = false" @add="addConnection" @edit="editConnection" @remove="removeConnection"
                      @test="testConnection"
                      @search="async keyword => { settingsConnections = await api.connections(null, keyword) }"/>
  <ConnectionFormDialog :show="dialogs.connectionForm" :connection="editingConnection"
                        @close="dialogs.connectionForm = false" @save="saveConnection" @test="testFormConnection"/>
  <HistoryDialog :show="dialogs.history" :items="historyItems" :loading="historyLoading"
                 :page="historyPage" :total-pages="historyTotalPages" :has-next="historyHasNext"
                 :page-size="historyPageSize" :total="historyTotal"
                 @close="dialogs.history = false" @page="loadHistory" @page-size-change="changeHistoryPageSize"/>
  <ConvertDialog :show="dialogs.convert" :connections="connections" @close="dialogs.convert = false" @use-result="runConverted" @execute-all="runConvertedAll"/>
  <SchemaDiffDialog :show="dialogs.diff" :connections="connections" @close="dialogs.diff = false"
                    @run="runDiffScript" @toast="toast"/>
  <div v-if="notice" class="toast">{{ notice }}</div>
</template>
