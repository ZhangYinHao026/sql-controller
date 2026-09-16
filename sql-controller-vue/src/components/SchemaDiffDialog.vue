<script setup>
/**
 * 字段差异对比面板（Vue 移植版）
 *
 * 语义来源：index/yuanxing.html「字段差异对比 v2」，经评审后固化：
 *  1) 补丁按目标库分桶：每个比对库一桶 + 基准库一桶（反向补齐）。生成只累积不打断，
 *     弹窗左侧列出目标库清单（待执行/执行中/已执行/失败），可逐库或「执行全部」统一执行；
 *  2) 执行 = 「▶ 执行到 目标库」，仅向该桶归属库发送（App.runDiffScript 负责切库型/跨方言转换/危险确认），
 *     成功即清空该桶脚本并就地显示状态，失败保留可重试；
 *  3) 跨方言补丁保持 MySQL 风格，由后端 SqlConvertService 翻译（DROP/MODIFY 已支持，P2 完成）。
 *
 * 数据源：App.vue 传入已保存连接(connections)，本组件按需调 /api/diff/schema-snapshot 抓结构快照
 * （快照缓存在组件实例内，可「⟳ 重抓快照」force 刷新）。
 */
import {computed, onBeforeUnmount, onMounted, reactive, ref, watch} from 'vue'
import {api} from '../api'
import {eqName, genCreateTable, indexCols, metaParts, mergedColsIx, sqlStr, statTable, statTableIx, stmtsFor, toEngineCol, appendOrReplaceBlock} from '../utils/schemaDiff'
import {typeLabel} from '../utils/dbRegistry'
import ConfirmDialog from './ConfirmDialog.vue'
import DiffPatchDialog from './DiffPatchDialog.vue'
import DiffRow from './DiffRow.vue'

const props = defineProps({show: Boolean, connections: {type: Array, default: () => []}})
const emit = defineEmits(['close', 'run', 'toast'])

/* ---------------- 状态 ---------------- */
const libs = reactive({})            // connId → {id,name,dbType,fetchedAt,error,tables:{t:[engineCol]}}
const fetching = ref(false)
const diffBase = ref(null)           // 基准连接 id
const diffCmps = ref([])             // 勾选的比对库 id（有序）
const diffCmp = ref(null)            // 单表核对激活的比对库 id
const diffTable = ref(null)
const diffMode = ref('summary')
const search = ref('')
const onlyDiff = ref(false)
const detailOnlyDiff = ref(false)   // 单表核对：仅显示差异字段（隐藏「一致」行）
const diffState = reactive({})       // `${cmp}|${table}|${field}` -> 'add'|'drop'|'mod'|'skip'|'back'
const applied = reactive({})         // `${cmp}|${table}` -> true
/* ---- 补丁：按目标库分桶（每个比对库一桶 + 基准库一桶），生成只累积、可攒批统一执行 ----
 *   key = String(目标库 connectionId)；status: idle(待执行) / running(执行中) / done(成功·已清空文本) / fail(失败·可重试) */
const patchBuckets = reactive({})        // key -> {id, text, edited, status, info}
const patchOrder = ref([])               // 有序 key（列表顺序 = 创建顺序）
const patchActive = ref(null)            // 弹窗当前选中的 key
const patchOpen = ref(false)             // 补丁弹窗是否打开
const guard = reactive({show: false, message: '', okText: '继续并清空', danger: true})
let guardCb = null                       // 确认后执行的回调

/* ---------------- 库 / 表访问 ---------------- */
const libOf = id => libs[id]
const connOf = id => props.connections.find(c => c.id === id)
const libName = id => (libOf(id) && libOf(id).name) || (connOf(id) && connOf(id).regionName) || id
const libTypeName = id => { const c = connOf(id); return c ? c.dbType : '' }
const okLibs = computed(() => props.connections.filter(c => libs[c.id] && !libs[c.id].error))
/** 该连接本会话是否已有成功快照（fetchedAt 非空）。无快照 = 尚未点过拉取 / 拉取失败。 */
const hasSnap = id => !!(libs[id] && libs[id].fetchedAt)
// 表名按标识符「忽略大小写」定位：DM/神通未加引号对象一律存储为大写、MySQL 侧常为小写，
// 跨方言比对时同一张表若仅大小写不同必须判定为存在，否则整表被误报「缺失」。
// 快照落地即建 tU 索引 → 此处 O(1) 定位；无索引（老数据结构/容错）时退回线性扫。
const tableRealKey = (id, t) => {
  const lib = libs[id]
  if (!lib || t == null) return null
  if (lib.tU && lib.tU.size) return lib.tU.get(String(t).toUpperCase()) ?? null
  const hit = Object.keys(lib.tables || {}).find(k => eqName(k, t))
  return hit == null ? null : hit
}
const hasTbl = (id, t) => tableRealKey(id, t) != null
const libTablesOf = id => (libs[id] && Object.keys(libs[id].tables || {})) || []
/** 取某库某表列：表名忽略大小写定位真实 key，列对象保留库内原始写法（含大写）。 */
const fieldsOf = (id, t) => {
  const k = tableRealKey(id, t)
  return (libs[id] && k && libs[id].tables[k]) || []
}
/** 取某库某表的列索引（indexCols 产物），O(1)；无快照/无表返回 null。 */
const colIdxOf = (id, t) => {
  const lib = libs[id]
  const k = tableRealKey(id, t)
  return (lib && k && lib.cI && lib.cI.get(k)) || null
}

const summaryText = computed(() => {
  if (diffBase.value == null) return '未选择基准库'
  const b = libs[diffBase.value]
  if (!b) return `基准「${libName(diffBase.value)}」尚未拉取快照 —— 勾选比对库后点「▶ 拉取快照」`
  if (b.error) return `基准「${libName(diffBase.value)}」快照失败：${b.error}`
  if (!diffCmps.value.length) return '请至少勾选一个比对库'
  // 勾选库中存在未拉取快照的：不产出统计，避免「空快照全库缺表」的假象，明确等待手动拉取
  const pending = diffCmps.value.filter(id => !hasSnap(id))
  if (pending.length) return `已勾选 ${diffCmps.value.length} 个比对库，其中 ${pending.length} 个尚未拉取快照（${pending.map(libName).join('、')}）——点「▶ 拉取快照」后再比对`
  // 仅显示「基准 → N 个比对库」，不再带数字；明细统计交给总览卡片与单表核对顶部 chips
  return `${libName(diffBase.value)}（基准） → ${diffCmps.value.length} 个比对库`
})

/* ---- 分桶访问 / 派生 ---- */
const bucketKey = id => String(id)
function bucketOf(id) {
  const k = bucketKey(id)
  if (!patchBuckets[k]) {
    patchBuckets[k] = {id, text: '', edited: false, status: 'idle', info: ''}
    if (!patchOrder.value.includes(k)) patchOrder.value.push(k)
  }
  return patchBuckets[k]
}
function dropBucket(k) {
  if (patchBuckets[k]) delete patchBuckets[k]
  patchOrder.value = patchOrder.value.filter(x => x !== k)
  if (patchActive.value === k) patchActive.value = null
}
const orderedBuckets = computed(() => patchOrder.value.map(k => patchBuckets[k]).filter(Boolean))
/** 桶所属角色：目标=当前基准库 → 'base'（基准补丁），其余比对库 → 'cmp'（比对补丁）。 */
const bucketRole = id => (diffBase.value != null && String(diffBase.value) === String(id)) ? 'base' : 'cmp'
const bucketRoleName = id => (bucketRole(id) === 'base' ? '基准补丁' : '比对补丁')

const patchLinesOf = b => (b.text.trim() ? b.text.trim().split('\n').length : 0)
const patchHasDrop = b => /DROP TABLE|DROP COLUMN|DELETE FROM|UPDATE |TRUNCATE/i.test(b.text)
const patchHasMod = b => /MODIFY COLUMN/i.test(b.text)
const patchMetaText = b => {
  const parts = []
  if (patchLinesOf(b)) parts.push(`已生成 ${patchLinesOf(b)} 行`)
  if (patchHasDrop(b)) parts.push('含 DROP（危险）')
  if (patchHasMod(b)) parts.push('含 MODIFY')
  if (b.edited) parts.push('已手工修改')
  return parts.join(' · ')
}
const anyRunning = computed(() => orderedBuckets.value.some(b => b.status === 'running'))
/** 待执行桶（有内容且未在执行中）。 */
const pendingBuckets = computed(() => orderedBuckets.value.filter(b => b.text.trim() && b.status !== 'running'))
/** 工具栏补丁入口徽标：N 库 · M 行；全清/全执行完只剩状态时显示已执行 N 库。 */
const patchBadge = computed(() => {
  const pend = pendingBuckets.value
  if (pend.length) return `${pend.length} 库 · ${pend.reduce((s, b) => s + patchLinesOf(b), 0)} 行`
  const marked = orderedBuckets.value.filter(b => b.status === 'done' || b.status === 'fail')
  return marked.length ? `已执行/失败 ${marked.length} 库` : ''
})
const patchEntryEnabled = computed(() => !!patchBadge.value || orderedBuckets.value.length > 0)
/* 弹窗视图数据（DiffPatchDialog 只读展示 + 事件回写；computed 缓存稳定引用） */
const patchViews = computed(() => orderedBuckets.value.map(b => ({
  key: bucketKey(b.id),
  name: libName(b.id),
  roleName: bucketRoleName(b.id),
  dbType: libTypeName(b.id),
  lines: patchLinesOf(b),
  meta: patchMetaText(b),
  status: b.status,
  info: b.info,
  hasText: !!b.text.trim(),
  canExec: !!b.text.trim() && b.status !== 'running',
  execLabel: `▶ 执行到 ${libName(b.id)}`,
  dirty: patchHasDrop(b) || patchHasMod(b)
})))
const activeBucketView = computed(() => {
  const k = patchActive.value
  const b = k ? patchBuckets[k] : null
  if (!b) return null
  return {
    key: bucketKey(b.id), name: libName(b.id), roleName: bucketRoleName(b.id), dbType: libTypeName(b.id),
    text: b.text, lines: patchLinesOf(b), meta: patchMetaText(b), status: b.status, info: b.info,
    canExec: !!b.text.trim() && b.status !== 'running', edited: b.edited,
    execLabel: `▶ 执行到 ${libName(b.id)}`, dirty: patchHasDrop(b) || patchHasMod(b)
  }
})

/* ---------------- 统计聚合（一次计算、多处复用） ----------------
 * cmpStats：基准库 × 每个已勾选比对库 的表级差异统计缓存。
 *   仅依赖「快照数据 + 基准选择 + 勾选集合」→ 只在切换库 / 重抓快照时重算一遍，
 *   summaryText / ovCards / 左栏 badge / genLibAdds 全部读它，互不重复 statTable。
 *   列勾选（diffState）与 applied 标记不参与 → 勾选动作零重算。 */
const cmpStats = computed(() => {
  const bId = diffBase.value
  const bLib = bId != null ? libs[bId] : null
  const out = new Map()
  if (!bLib || !bLib.tables) return out
  for (const c of diffCmps.value) {
    const cLib = libs[c]
    const entry = { sums: { add: 0, drop: 0, alt: 0, pk: 0, missing: 0 }, byT: new Map() }
    if (cLib && cLib.tables) {
      for (const t of Object.keys(bLib.tables)) {
        const cK = tableRealKey(c, t)
        if (cK == null) { entry.sums.missing++; entry.byT.set(t, { missing: true, st: null }); continue }
        const st = statTableIx(colIdxOf(bId, t), colIdxOf(c, t))
        entry.sums.add += st.add; entry.sums.drop += st.drop; entry.sums.alt += st.alt; entry.sums.pk += st.pk
        entry.byT.set(t, { missing: false, st })
      }
    }
    out.set(c, entry)
  }
  return out
})
/** 取某比对库的聚合条目（无快照时返回空条目，sums 全 0）。 */
const cmpEntry = c => cmpStats.value.get(c) || { sums: { add: 0, drop: 0, alt: 0, pk: 0, missing: 0 }, byT: new Map() }
/* 注：原 statAll() 已删（summaryText 简化后无调用方；明细在总览卡 / 单表核对 chips / 左栏徽章里展示）。 */
/** 表清单 = 基准表 + 比对库独有表，按标识符忽略大小写折叠（基准名优先展示）。 */
function allTables() {
  const out = []
  const push = k => { if (k != null && !out.some(x => eqName(x, k))) out.push(k) }
  libTablesOf(diffBase.value).forEach(push)
  diffCmps.value.forEach(c => libTablesOf(c).forEach(push))
  return out
}
/** 单表徽章（左栏）：从聚合读，O(勾选库数)；非基准表（比对库独有）退化直算（稀有）。
 *  仅在有表缺失的极端情形下与 applied 标记判断需逐库扫。 */
function badgeFor(t) {
  const list = diffCmps.value
  if (list.length && list.every(c => applied[c + '|' + t])) return { cls: 'applied', txt: '✓ 已应用', title: '已应用补丁（按比对库逐个勾选标记）' }
  let missing = 0, add = 0, drop = 0, alt = 0, pk = 0
  const baseHas = hasTbl(diffBase.value, t)
  for (const c of list) {
    if (!baseHas) {
      // 比对库独有表：有则按列数记 drop，无则记 missing（与旧 statTable([], R) 语义一致且 O(列数)）
      if (!hasTbl(c, t)) { missing++; continue }
      const cols = fieldsOf(c, t)
      drop += cols.length
      continue
    }
    const hit = cmpEntry(c).byT.get(tableRealKey(diffBase.value, t))
    if (!hit) { missing++; continue }
    if (hit.missing) { missing++; continue }
    const s = hit.st
    add += s.add; drop += s.drop; alt += s.alt; pk += s.pk
  }
  if (missing === list.length && list.length) return { cls: 'missing', txt: '缺表×' + missing, title: '所有比对库均缺该表' }
  const parts = []
  if (missing) parts.push('缺表×' + missing)         // 缺整表是重大差异，优先展示（避免被「其余库一致」掩盖）
  if (add) parts.push('缺' + add)
  if (drop) parts.push('多' + drop)
  if (alt) parts.push('核' + alt)
  if (pk) parts.push('主键' + pk)
  if (!parts.length) return { cls: 'ok', txt: '✓ 一致', title: '与所有勾选比对库一致' }
  const cls = missing ? 'missing' : pk ? 'pk' : drop ? 'drop' : add ? 'add' : 'alt'   // missing 红底优先
  return { cls, txt: parts.join(' '), title: '差异明细：' + parts.join(' · ') }
}
/** 左栏清单（computed 化：Vue 依赖追踪，search/onlyDiff/勾选变化才重算）。
 *  每行携带预计算的徽章 {t, badge}，模板只读属性，不再每渲染重复调 badgeFor。 */
const filteredTables = computed(() => {
  const kw = search.value.trim().toLowerCase()
  const list = []
  for (const t of allTables()) {
    if (kw && !t.toLowerCase().includes(kw)) continue
    const badge = badgeFor(t)
    if (onlyDiff.value && badge.cls === 'ok') continue
    list.push({ t, badge })
  }
  return list
})
/** 搜索词非空：差异总览卡片行/统计按其过滤；启用时隐藏「自动补齐/补表」等批量按钮（其作用范围仍是整库，与过滤视图错位）。 */
const kwActive = computed(() => !!search.value.trim())

/* ---------------- 快照抓取 ---------------- */
/** 拉取前置条件：基准 + ≥1 个比对库；打开面板不自动抓，用户选好后再手动拉取（按需，缓存命中秒回）。 */
const fetchReady = computed(() => diffBase.value != null && diffCmps.value.length > 0)

/** 本次抓取范围 = 基准 + 已勾选比对库（勾选全部即为全量；不勾的库不抓）。 */
function scopeIds() {
  const ids = []
  if (diffBase.value != null) ids.push(diffBase.value)
  diffCmps.value.forEach(id => { if (!ids.includes(id)) ids.push(id) })
  return ids
}

function applySnaps(snaps) {
  snaps.forEach(s => {
    const conn = props.connections.find(c => c.id === s.connectionId)
    const key = String(s.connectionId)
    if (!conn) return
    if (s.ok) {
      const tables = {}
      const tU = new Map()   // 大写表名 → 真实表名（eqName O(1) 定位）
      const cI = new Map()   // 真实表名 → indexCols 列索引
      ;(s.tables || []).forEach(ti => {
        const cols = (ti.columns || []).map(toEngineCol)
        tables[ti.name] = cols
        tU.set(String(ti.name).toUpperCase(), ti.name)
        cI.set(ti.name, indexCols(cols))
      })
      libs[key] = {id: s.connectionId, name: conn.regionName, dbType: conn.dbType, fetchedAt: s.fetchedAt, error: null, tables, tU, cI}
    } else {
      libs[key] = {id: s.connectionId, name: conn.regionName, dbType: s.dbType || conn.dbType, fetchedAt: null, error: s.error || '抓取失败', tables: {}, tU: new Map(), cI: new Map()}
    }
  })
}

/** 「▶ 拉取快照」：按当前勾选范围抓取（优先后端 TTL 缓存）；「⟳ 强制重抓」同范围跳过缓存。 */
async function fetchAll(force = false) {
  if (!props.connections.length) return toast('请先在「设置」中添加数据库连接')
  if (!fetchReady.value) return toast('请先选择基准库并勾选至少一个比对库')
  const ids = scopeIds()
  fetching.value = true
  try {
    const snaps = await api.schemaSnapshot(ids, force)
    applySnaps(snaps)
    const failed = snaps.filter(s => !s.ok).length
    if (failed) toast(`${snaps.length - failed}/${snaps.length} 个库快照成功${failed ? `，${failed} 个失败（详见比对库勾选区）` : ''}`)
  } catch (e) {
    toast('快照抓取失败：' + e.message)
  } finally {
    fetching.value = false
  }
}

/* ---------------- 打开 / 关闭 ---------------- */
// 打开面板不自动抓取：由用户选好「基准 + 比对库」后手动触发，避免一打开就全量慢抓/超时

/* ---------------- 守卫（存在待执行补丁时的「作废性」操作确认） ----------------
 * 各库补丁独立分桶：切换/取消勾选比对库不影响任何桶（内容不丢，不再弹确认）；
 * 只有「切换基准库 / 撤销清空」会让已生成脚本整体失去意义 → 清空前确认。 */
function requestGuard(actionText, fn, opts = {}) {
  const dirty = pendingBuckets.value
  if (!dirty.length) { fn(); return }
  const desc = dirty.length > 3
    ? dirty.slice(0, 3).map(b => libName(b.id)).join('、') + ` 等 ${dirty.length} 个目标库`
    : dirty.map(b => libName(b.id)).join('、')
  guard.message = `${desc} 的补丁（${dirty.length} 份 · ${dirty.reduce((s, b) => s + patchLinesOf(b), 0)} 行）还未执行/复制。\n${actionText}将先清空这些脚本，未执行/复制的脚本会丢失。`
  guard.okText = opts.okText || '继续并清空'
  guard.danger = opts.danger !== false
  guardCb = fn
  guard.show = true
}
function guardOk() {
  guard.show = false
  const fn = guardCb
  guardCb = null
  if (fn) fn()
}
function guardCancel() { guard.show = false; guardCb = null }
function clearAllBuckets() {
  patchOrder.value.forEach(k => { delete patchBuckets[k] })
  patchOrder.value = []
  patchActive.value = null
  patchOpen.value = false
}

/* ---------------- 选择动作 ---------------- */
function setDiffMode(m) {
  diffMode.value = m
  if (m === 'detail' && diffCmp.value && !diffTable.value) diffTable.value = allTables()[0] || null
}
function selectTable(t) {
  diffTable.value = t
  if (!diffCmp.value) diffCmp.value = diffCmps.value[0] || null
  diffMode.value = 'detail'
}
function selectCmp(id) {
  if (id === diffCmp.value) return
  diffCmp.value = id
  persist()
}
function goCmpDetail(id) {
  if (id === diffCmp.value) { diffMode.value = 'detail'; return }
  diffCmp.value = id
  diffMode.value = 'detail'
  persist()
}
function goCmpTable(id, t) {
  diffCmp.value = id
  diffTable.value = t
  diffMode.value = 'detail'
  persist()
}
function onBaseChange(e) {
  const next = Number(e.target.value)
  if (next === diffBase.value) return
  const nm = libName(next)
  const apply = () => {
    diffBase.value = next
    diffCmps.value = diffCmps.value.filter(id => id !== next)
    if (!diffCmps.value.includes(diffCmp.value)) diffCmp.value = diffCmps.value[0] || null
    diffTable.value = null
    clearState()
    clearAllBuckets()
  }
  // 换基准 = 全部补丁/标记失去基准语义 → 确认后清空；未拉快照也可先选中（点「▶ 拉取快照」一并抓）
  requestGuard(`切换基准库到「${nm}」`, apply)
}
function toggleCmp(id, on) {
  const lib = libs[id]
  if (on) {
    if (lib && lib.error) return toast(`「${libName(id)}」快照失败，无法对比：${lib.error}`)
    diffCmps.value = [...diffCmps.value, id]
    if (!diffCmp.value) diffCmp.value = id
    persist()
  } else {
    doToggleCmpOff(id)
  }
}
function doToggleCmpOff(id) {
  diffCmps.value = diffCmps.value.filter(x => x !== id)
  if (diffCmp.value === id) diffCmp.value = diffCmps.value[0] || null
  persist()
}
function persist() { /* 快照/选择依赖真实连接，不做跨会话持久化，避免陈旧 id 误配 */ }

function clearState() {
  Object.keys(diffState).forEach(k => delete diffState[k])
  Object.keys(applied).forEach(k => delete applied[k])
}

/* ---------------- 生成 ---------------- */
function stKey(cmpId, t, n) { return cmpId + '|' + t + '|' + n }
function choiceOn(cmpId, t, name, kind) {
  const st = diffState[stKey(cmpId, t, name)]
  if (kind === 'add') return st !== 'skip'
  return st === kind
}
function setChoice(n, kind, on) {
  const k = stKey(diffCmp.value, diffTable.value, n)
  if (kind === 'add') diffState[k] = on ? 'add' : 'skip'
  else if (on) diffState[k] = kind
  else delete diffState[k]
}
/** DiffRow 行勾选回调：仅写 diffState 单键，detailRows 不依赖它 → 不触发整表重算。 */
function onRowChoice({name, kind, on}) {
  setChoice(name, kind, on)
}
/** 追加补丁到指定目标库的桶。桶按库隔离、天然不混库；同一目标库内同一表重复生成时**替换原 block**，
 *  避免 SQL 叠加重复执行。追加不自动弹窗：内容进「📋 补丁脚本」入口（徽章计数），可攒批后统一执行。 */
function patchAppendTo(ownerId, block) {
  const b = bucketOf(ownerId)
  b.text = appendOrReplaceBlock(b.text, block)
  b.edited = false
  if (b.status !== 'running') { b.status = 'idle'; b.info = '' }
}
/** 弹窗手工编辑回调：写回当前激活桶并打「已手工修改」标（新键入视为新一轮待执行）。 */
function onPatchInput(value) {
  const b = patchActive.value ? patchBuckets[patchActive.value] : null
  if (!b) return
  b.text = value
  b.edited = true
  if (b.status === 'done' || b.status === 'fail') b.status = 'idle'
  b.info = ''
}
function genDetail() {
  if (diffCmp.value == null || !diffTable.value) return
  const t = diffTable.value, cmpId = diffCmp.value
  const baseId = diffBase.value
  /* —— 「补基准库」分支：drop 行（基准缺列）勾选 back 后，目标库=基准库 —— */
  const backCols = detailRows.value.filter(r => r.kind === 'drop' && diffState[r.stateKey] === 'back')
  if (backCols.length) {
    // 与 back 冲突的是用户「显式勾选」的向比对库动作；ADD 默认勾（stateKey 无值 = choiceOn 默认 true）
    // 只是安全默认、并非用户意图，back 模式下不生成它也不视为冲突。
    const fwdOn = detailRows.value.some(r => {
      if (r.kind === 'add') return diffState[r.stateKey] === 'add'   // 仅显式勾选
      if (r.kind === 'drop') return diffState[r.stateKey] === 'drop'
      if (r.kind === 'alt') return diffState[r.stateKey] === 'mod'
      return false
    })
    if (fwdOn) return toast(`表 \`${t}\` 同时显式勾选了“向比对库同步”与“补基准库”，两目标库不同无法一次生成；请先取消一侧后重试`)
    const baseName = libName(baseId), cmpName = libName(cmpId)
    const notes = []
    const parts = backCols.map(r => 'ADD COLUMN ' + addColDef(r.r, notes))
    const block = `-- 【补基准库】${baseName}（依据比对库 ${cmpName} 补齐缺列）· 表 \`${t}\`\nALTER TABLE \`${t}\` ` + parts.join(', ') + ';'
    patchAppendTo(baseId, block)
    applied[cmpId + '|' + t] = true
    toast(`已将 ${parts.length} 个缺列补丁加入「${bucketRoleName(baseId)}」（目标库：${baseName}），可稍后从 📋 补丁脚本 统一执行`)
    return
  }
  const res = stmtsFor(fieldsOf(baseId, t), fieldsOf(cmpId, t), t, (n, kind) => choiceOn(cmpId, t, n, kind))
  if (!res.stmts.length) {
    const missing = !hasTbl(cmpId, t)
    const st = missing ? null : statTable(fieldsOf(baseId, t), fieldsOf(cmpId, t))
    const hint = missing ? '目标缺少整表' : st && st.pk && !st.add && !st.drop && !st.alt ? '该表差异仅为主键差异（不自动处理）' : '未勾选任何动作'
    return toast('该表无可生成项（' + hint + '）')
  }
  const base = libName(baseId), cmp = libName(cmpId)
  let block = `-- 【${cmp}】← 基准 ${base} · 表 \`${t}\`\n` + res.stmts.join('\n')
  if (res.notes.length) block += '\n-- 提示：' + res.notes.join('\n--       ')
  patchAppendTo(cmpId, block)
  applied[cmpId + '|' + t] = true
  toast(`已生成 ${res.stmts.length} 条 ALTER 加入「${cmp}」待执行桶（可继续其它库生成，稍后统一执行）`)
}
function genLibAdds(cmpId) {
  genLibAddsInner(cmpId)
}
function genLibAddsInner(cmpId) {
  const baseName = libName(diffBase.value), cmpName = libName(cmpId)
  const blocks = [], notes = []
  libTablesOf(diffBase.value).forEach(t => {
    if (!hasTbl(cmpId, t)) { notes.push('比对库缺整表 `' + t + '`（未自动建表，请用转换器）'); return }
    const li = colIdxOf(diffBase.value, t), ri = colIdxOf(cmpId, t)
    const empty = { list: [], byUpper: new Map() }
    const adds = []
    mergedColsIx(li || empty, ri || empty).forEach(row => {
      if (row.cs.kind === 'add') adds.push('ADD COLUMN ' + addColDef(row.l, notes))
    })
    if (adds.length) blocks.push('ALTER TABLE `' + t + '` ' + adds.join(', ') + ';')
  })
  if (!blocks.length) return toast(cmpName + '：无缺列可补' + (notes.length ? '（' + notes[0] + '）' : ''))
  patchAppendTo(cmpId, `-- ===== ${cmpName}（${libTypeName(cmpId)}）← 基准 ${baseName} · 自动补齐缺列 =====\n` + blocks.join('\n') + (notes.length ? '\n-- 提示：' + [...new Set(notes)].join('\n--       ') : ''))
  // 已补全的表标记 ✓ 已应用（读聚合，不重算 statTable）
  cmpEntry(cmpId).byT.forEach((hit, t) => {
    if (!hit.missing) {
      const s = hit.st
      if (!s.add && !s.drop && !s.alt && !s.pk) applied[cmpId + '|' + t] = true
    }
  })
  toast(`已为「${cmpName}」生成 ${blocks.length} 张表缺列补丁（${patchLinesOf(bucketOf(cmpId))} 行待执行，可从 📋 补丁脚本 查看/统一执行）`)
}
function addColDef(f, notes) {
  // 与 schemaDiff.genAdd 同语义；此处需独立产出（表级循环没有逐列 stmtsFor choose 上下文）
  let s = '`' + f.name + '` ' + f.type
  const isText = /TEXT|BLOB|CLOB|JSON|XML/i.test(f.type)
  const noDefault = f.default === undefined || f.default === null
  if (f.notNull) {
    if (noDefault && !isText) { s += ' NULL'; notes.push('列 `' + f.name + '` NOT NULL 无默认值，跨方言 ADD 未自动兜底，请人工补 DEFAULT 后执行') }
    else if (noDefault && isText) { s += ' NULL'; notes.push('列 `' + f.name + '` 为 ' + f.type + ' 且 NOT NULL，已退化为可空 NULL（请人工确认）') }
    else { s += ' NOT NULL'; if (!noDefault) s += ' DEFAULT ' + (typeof f.default === 'number' ? f.default : sqlStr(f.default)) }
  } else if (!noDefault) s += ' DEFAULT ' + (typeof f.default === 'number' ? f.default : sqlStr(f.default))
  if (f.comment) s += ' COMMENT ' + sqlStr(f.comment)
  return s
}

/* ---------------- 缺整表直接补（总览页） ----------------
 * 缺整表 = 比对库无此表：列级 ADD/MODIFY 无从谈起 → 直接按基准快照生成 MySQL 风格 CREATE TABLE，
 * 并入「比对补丁」槽（owner=比对库），执行时跨方言由 App 侧转换器自动翻译（同 ALTER 链路）。 */
function genCreateBlock(cmpId, t) {
  const cols = fieldsOf(diffBase.value, t)
  if (!cols.length) return null
  const base = libName(diffBase.value), cmp = libName(cmpId)
  const cross = libTypeName(cmpId) !== 'mysql'
  const head = `-- 【${cmp}】缺整表建表 ← 基准 ${base} · 表 \`${t}\`（MySQL 风格${cross ? '，执行时自动转换 ' + typeLabel(libTypeName(cmpId)) + ' 方言' : ''}）`
  return head + '\n' + genCreateTable(t, cols)
}
/** 单张缺表 → 生成建表脚本并标记已应用（入该库待执行桶，不弹窗）。 */
function genOneCreate(cmpId, t) {
  const block = genCreateBlock(cmpId, t)
  if (!block) return toast(`基准库无表 \`${t}\` 的列快照，无法生成建表脚本`)
  patchAppendTo(cmpId, block)
  applied[cmpId + '|' + t] = true
  toast(`已生成建表脚本入「${libName(cmpId)}」待执行桶 · ${t}（可从 📋 补丁脚本 统一执行）`)
}
/** 批量：当前卡全部缺整表 → 各生成一条 CREATE 并入该库待执行桶（逐表追加，同表重复生成幂等替换）。 */
function genMissingTables(cmpId) {
  const missing = libTablesOf(diffBase.value).filter(t => !hasTbl(cmpId, t))
  if (!missing.length) return toast(libName(cmpId) + '：无缺整表可补')
  let n = 0
  missing.forEach(t => {
    const block = genCreateBlock(cmpId, t)
    if (!block) return
    patchAppendTo(cmpId, block) // 每表独立 block：appendOrReplaceBlock 按各表注释头幂等替换
    applied[cmpId + '|' + t] = true
    n++
  })
  if (!n) return toast(libName(cmpId) + '：缺表均无基准列快照，无法生成')
  toast(`已为「${libName(cmpId)}」生成 ${n} 张缺表建表脚本（${patchLinesOf(bucketOf(cmpId))} 行待执行）`)
}

/* ---------------- 补丁弹窗操作（按激活桶 key） ---------------- */
function openPatch(preferId) {
  if (preferId != null && patchBuckets[bucketKey(preferId)]) patchActive.value = bucketKey(preferId)
  else if (!patchActive.value || !patchBuckets[patchActive.value] || !patchBuckets[patchActive.value].text.trim()) {
    patchActive.value = patchOrder.value.find(k => patchBuckets[k].text.trim()) || patchOrder.value[0] || null
  }
  patchOpen.value = true
}
function closePatch() { patchOpen.value = false }
function selectBucket(k) { patchActive.value = k }
function clearCurPatch() {
  const k = patchActive.value
  const b = k ? patchBuckets[k] : null
  if (!b) return toast('当前没有选中的补丁桶')
  b.text = ''; b.edited = false; b.status = 'idle'; b.info = ''
  toast(`已清空「${libName(b.id)}」补丁`)
}
function copyCurPatch() {
  const k = patchActive.value
  const b = k ? patchBuckets[k] : null
  if (!b || !b.text.trim()) return toast('补丁为空，请先生成')
  navigator.clipboard?.writeText(b.text.trim()).catch(() => {})
  toast(`${libName(b.id)} 补丁 SQL 已复制`)
}
function runCurPatch() {
  const k = patchActive.value
  if (k && patchBuckets[k] && patchBuckets[k].text.trim()) runBucket(Number(k))
}
/** 单桶执行（异步；onDone 由 App 保证在成功/失败后均回调，返回 Promise 便于 runAll 串行）。 */
function runBucket(id) {
  const b = bucketOf(id)
  const sqlText = b.text.trim()
  if (!sqlText) return Promise.resolve(false)
  b.status = 'running'
  b.info = '执行中…'
  const t0 = Date.now()
  return new Promise(resolve => {
    emit('run', {
      connectionId: id,
      sql: sqlText,
      onDone: res => {
        const ok = !!(res && res.ok)
        const used = Math.round((Date.now() - t0) / 100) / 10
        if (ok) {
          b.status = 'done'
          b.info = `✓ ${new Date().toLocaleTimeString('zh-CN', {hour12: false})} 执行成功` + (res.rows != null ? ` · ${res.rows} 行受影响` : '') + ` · ${used}s`
          b.text = ''; b.edited = false   // 已执行到库不再保留，防残留重复执行；状态保留可回溯
          toast(`已执行到 ${libName(id)} 成功，该库补丁已清空`)
          refreshTablesAfterExec(id, sqlText)
        } else {
          b.status = 'fail'
          b.info = `✗ ${new Date().toLocaleTimeString('zh-CN', {hour12: false})} 执行失败 · ${(res && res.error) || '未知错误（详见主区结果）'} · 可重试`
        }
        resolve(ok)
      }
    })
  })
}
/** 执行全部待执行桶（串行：每桶走 App 统一执行链路，含各自的危险确认/方言转换）。 */
async function runAllPending() {
  const pend = pendingBuckets.value
  if (!pend.length) return toast('没有待执行的补丁')
  if (anyRunning.value) return toast('已有补丁在执行中，请等待完成')
  let okN = 0
  for (const b of pend) {
    if (await runBucket(b.id)) okN++
  }
  const failN = pend.length - okN
  toast(`批量执行完成：成功 ${okN}/${pend.length} 个目标库${failN ? `，失败 ${failN} 个（列表中可重试）` : ''}`)
}

/* ---------------- 执行后局部刷新 ----------------
 * 补丁执行成功（App 侧 execute 无 FAILED）后由 App 回调 onDone →
 * 只重抓脚本里涉及的这几张表（后端按表查询），合并进本地快照：
 * 左栏「缺/核/一致」徽章与总览统计随即重算，不做整库刷新。 */
const patchTableRe = /\b(?:ALTER|CREATE|DROP|TRUNCATE)\s+TABLE(?:\s+IF\s+(?:NOT\s+)?EXISTS)?\s+((?:`[^`]+`|"[^"]+"|\[[^\]]+\]|[\w$]+)(?:\s*\.\s*(?:`[^`]+`|"[^"]+"|\[[^\]]+\]|[\w$]+))?)/gi
function parsePatchTables(sqlText) {
  const names = new Set()
  let m
  while ((m = patchTableRe.exec(sqlText))) {
    let t = m[1].trim().replace(/`|"|\[|\]/g, '')
    t = t.split('.').pop().trim() // 剥 schema/db 前缀
    if (t) names.add(t)
  }
  return [...names]
}
/** 表结构已同步：作废该表全部比对状态（含所有比对库），徽章按新快照自动重算 */
function clearStatesOfTable(t) {
  for (const k of Object.keys(diffState)) {
    const p = k.split('|')
    if (p.length >= 3 && eqName(p[1], t)) delete diffState[k]
  }
  for (const k of Object.keys(applied)) {
    const p = k.split('|')
    if (p.length === 2 && eqName(p[1], t)) delete applied[k]
  }
}
async function refreshTablesAfterExec(connId, sqlText) {
  const lib = connId != null ? libs[connId] : null
  if (!lib) return // 该库本会话未抓过快照（理论不发生：能生成补丁必有快照）
  const tables = parsePatchTables(sqlText)
  if (!tables.length) return
  try {
    const snaps = await api.schemaTables(connId, tables)
    const list = Array.isArray(snaps) ? snaps : [snaps]
    const snap = list.find(s => s && String(s.connectionId) === String(connId))
    if (!snap || !snap.ok) {
      toast(`执行后刷新「${libName(connId)}」表结构失败：${(snap && snap.error) || '无响应'}`)
      return
    }
    const returned = new Map()
    ;(snap.tables || []).forEach(ti => returned.set(String(ti.name).toUpperCase(), ti))
    let n = 0
    tables.forEach(t => {
      const ti = returned.get(String(t).toUpperCase())
      const oldK = tableRealKey(connId, t)
      if (ti) {
        if (oldK && oldK !== ti.name) { delete lib.tables[oldK]; lib.tU.delete(String(oldK).toUpperCase()); lib.cI.delete(oldK) }
        const cols = (ti.columns || []).map(toEngineCol)
        lib.tables[ti.name] = cols
        lib.tU.set(String(ti.name).toUpperCase(), ti.name)
        lib.cI.set(ti.name, indexCols(cols))
        n++
      } else if (oldK) {
        // 后端已无此表（被 DROP）：移除本地条目，列表/徽章自然更新
        delete lib.tables[oldK]; lib.tU.delete(String(oldK).toUpperCase()); lib.cI.delete(oldK)
        n++
      }
      clearStatesOfTable(t)
    })
    if (n) toast(`已局部刷新 ${n} 张表结构：${tables.join('、')}`)
  } catch (e) {
    toast('执行后刷新表结构失败：' + e.message)
  }
}
/** 撤销「已应用」= 复位整个对比会话：状态/补丁/所选库/差异表列表全清（快照缓存保留，重选即回）。 */
function clearAll() {
  requestGuard('复位对比会话：撤销「已应用」标记、清空全部待执行补丁、清空所选基准/比对库与差异表列表',
    () => {
      clearState()
      clearAllBuckets()
      diffCmps.value = []
      diffCmp.value = null
      diffTable.value = null
      diffBase.value = null
      diffMode.value = 'summary'
      search.value = ''
      onlyDiff.value = false
      detailOnlyDiff.value = false
      toast('已复位：撤销「已应用」、清空补丁/所选库/差异表列表')
    })
}
/* ===== 关闭 =====
 * 需求：只有「×」按钮或 Esc 键关闭；点遮罩空白一律不关（diff 内容含大量勾选/脚本，防误触丢数据）。
 * 处理顺序：Esc 先关掉置顶的 guard 确认弹窗；无 guard 时若有补丁弹窗先关弹窗，再关整个面板。 */
function onEsc(e) {
  if (e.key !== 'Escape' || !props.show) return
  if (guard.show) { guardCancel(); return }
  if (patchOpen.value) { closePatch(); return }
  emit('close')
}
function toast(m) { emit('toast', m) }

/* ---------------- 单表核对视图（computed） ---------------- */
/** 差异类别汇总 → 徽章碎片（缺/多/核/主键/缺表，只列非零项）；全一致返回空。 */
function diffParts(s) {
  const p = []
  if (s.add) p.push({t: '缺' + s.add, cls: 'add'})
  if (s.drop) p.push({t: '多' + s.drop, cls: 'drop'})
  if (s.alt) p.push({t: '核' + s.alt, cls: 'alt'})
  if (s.pk) p.push({t: '主键' + s.pk, cls: 'pk'})
  if (s.missing) p.push({t: '缺表' + s.missing, cls: 'missing'})
  return p
}
/** 单表核对 pills：纯库名切换按钮（差异统计已全部移除，仅 title 悬浮保留明细提示）。 */
const cmpPillViews = computed(() => diffCmps.value.map(id => {
  const s = cmpEntry(id).sums
  const parts = diffParts(s)
  return {
    id,
    name: libName(id),
    title: parts.length ? parts.map(p => p.t).join(' · ') + `（相对基准 ${libName(diffBase.value)}）` : '与基准完全一致'
  }
}))
/** 比对库勾选 label 上的微型差异徽章（该库 vs 基准，未拉快照/失败返回 null）。 */
function cmpMini(id) {
  const lib = libs[id]
  if (!lib || lib.error || !lib.fetchedAt) return null
  const parts = diffParts(cmpEntry(id).sums)
  const title = parts.length ? parts.map(p => p.t).join(' · ') + `（相对基准 ${libName(diffBase.value)}）` : '与基准完全一致'
  if (!parts.length) return { cls: 'ok', txt: '✓', title }
  // chip 空间有限：列前两类非零差异；总量可在 title 悬浮看到
  return { cls: 'diff', txt: parts.slice(0, 2).map(p => p.t).join(' '), title }
}
const detailReady = computed(() => diffCmp.value != null && !!(libs[diffCmp.value] && libs[diffCmp.value].error === null) && !!diffBase.value && libs[diffBase.value])
const detailMissingTable = computed(() => detailReady.value && !!diffTable.value && !hasTbl(diffCmp.value, diffTable.value))
/** 行对象静态化：不再内嵌 addOn/dropOn/modOn（不读 diffState）→ 勾选动作不触发整表重算。
 *  差异描述 + meta 展示元（提前算好，渲染期不再逐行 metaParts）。
 *  勾选态改由子组件 DiffRow 内部读 stateKey，依赖范围收缩到单行。 */
const detailRows = computed(() => {
  if (!detailReady.value || !diffTable.value) return []
  const t = diffTable.value
  const li = colIdxOf(diffBase.value, t)
  const ri = colIdxOf(diffCmp.value, t)
  if (!ri) return [] // 缺整表走 missing 分支
  const empty = { list: [], byUpper: new Map() }
  return mergedColsIx(li || empty, ri)
    .filter(r => !detailOnlyDiff.value || r.cs.kind !== 'same')   // 「仅差异字段」：隐藏一致行
    .map(row => {
    const { name, l, r, cs } = row
    return {
      name, kind: cs.kind, reasons: cs.reasons, l, r,
      chip: { same: '一致', add: '缺列 · 补', drop: '多列 · 删', alt: '需核对', pk: '主键差异' }[cs.kind],
      metaL: l ? metaParts(l) : [], metaR: r ? metaParts(r) : [],
      stateKey: stKey(diffCmp.value, t, name)
    }
  })
})
const detailStat = computed(() => {
  if (!detailReady.value || !diffTable.value) return { missing: false, add: 0, drop: 0, alt: 0, pk: 0 }
  const t = diffTable.value
  if (!hasTbl(diffCmp.value, t)) return { missing: true, add: 0, drop: 0, alt: 0, pk: 0 }
  if (!hasTbl(diffBase.value, t)) return { missing: false, add: 0, drop: fieldsOf(diffCmp.value, t).length, alt: 0, pk: 0 }
  // 基准表：从聚合读（不重算 statTable）；个别未在聚合（无快照比对库）时退回直算
  const hit = cmpEntry(diffCmp.value).byT.get(tableRealKey(diffBase.value, t))
  if (hit && !hit.missing) return hit.st
  const st = statTable(fieldsOf(diffBase.value, t), fieldsOf(diffCmp.value, t))
  return { missing: false, add: st.add, drop: st.drop, alt: st.alt, pk: st.pk }
})
/** 单表核对：当前表在激活比对库缺失时，是否已为它生成过建表脚本（防重复点击）。 */
const detailCreateDone = computed(() =>
  detailMissingTable.value && !!applied[diffCmp.value + '|' + diffTable.value]
)

/* ---- 一键确认（同步动作 · 逐列确认总开关）----
 * 作用域 = 当前表 × 当前比对库；只影响有动作的行（add/drop/alt），pk 与 same 无动作不参与。
 * 全选方向统一为「对齐基准库」：add→ADD、drop→DROP、alt→MODIFY（drop 行已勾的 back 会被覆盖，
 * 因为 back 是反向补基准，与"全表对齐基准"语义冲突）。勾选后再逐行人工核对，最后点「✓ 生成选中动作」。 */
const allChkEl = ref(null)                       // 绑定 checkbox DOM 以设置 indeterminate 三态
const allChkState = computed(() => {
  const rs = detailRows.value.filter(r => r.kind === 'add' || r.kind === 'drop' || r.kind === 'alt')
  if (!rs.length) return { checked: false, indeterminate: false }
  const act = rs.map(r =>
    r.kind === 'add'  ? diffState[r.stateKey] !== 'skip'
    : r.kind === 'drop' ? diffState[r.stateKey] === 'drop'   // back 视为该行另有方向，不算"对齐基准"选中
    : diffState[r.stateKey] === 'mod')                       // alt
  const on = act.filter(Boolean).length
  return { checked: on === rs.length, indeterminate: on > 0 && on < rs.length }
})
// flush:'post' 确保在 v-if 块首次挂载后再写 indeterminate（'pre' 会在新 DOM 出现前执行而漏设首次态）
watch(allChkState, s => { if (allChkEl.value) allChkEl.value.indeterminate = s.indeterminate }, { immediate: true, flush: 'post' })
function toggleAllRows(on) {
  const cmpId = diffCmp.value, t = diffTable.value
  if (cmpId == null || t == null) return
  for (const r of detailRows.value) {
    const k = stKey(cmpId, t, r.name)
    if (r.kind === 'add') diffState[k] = on ? 'add' : 'skip'
    else if (r.kind === 'drop') { if (on) diffState[k] = 'drop'; else delete diffState[k] }
    else if (r.kind === 'alt') { if (on) diffState[k] = 'mod'; else delete diffState[k] }
  }
}
/** 由 stat 派生徽章文本/样式（读聚合结果，供总览卡使用）。 */
function badgeFromStat(s) {
  const cls = s.add ? 'b-add' : s.drop ? 'b-drop' : s.alt ? 'b-alt' : 'b-pk'
  const parts = []
  if (s.add) parts.push('缺' + s.add)
  if (s.drop) parts.push('多' + s.drop)
  if (s.alt) parts.push('核' + s.alt)
  if (s.pk) parts.push('主键' + s.pk)
  return { cls, txt: parts.join(' ') }
}

/* ---------------- 总览卡片（读聚合，按搜索词过滤行/统计） ---------------- */
const ovCards = computed(() => {
  if (!diffCmps.value.length) return []
  const kw = search.value.trim().toLowerCase()
  return diffCmps.value.map(c => {
    const conn = connOf(c)
    const db = libOf(c)
    // 尚未拉取快照：渲染「待拉取」占位卡（不放空快照的误导性统计）
    if (!db || !db.fetchedAt) return {id: c, db: {name: (conn && conn.regionName) || c, dbType: (conn && conn.dbType) || ''}, pending: true, ls: null, rows: []}
    const entry = cmpEntry(c)
    // 搜索词过滤：仅保留表名包含 kw 的表（忽略大小写），并据过滤后子集重算 sums；
    //   · 头部 stat 同步反映过滤视图（与左栏一致：搜表 → 全页聚焦此表）
    //   · 自动补齐/补表等批量按钮作用范围仍是整库，搜索中由模板另行隐藏
    const sums = { add: 0, drop: 0, alt: 0, pk: 0, missing: 0 }
    const rows = []
    entry.byT.forEach((hit, t) => {
      if (kw && !String(t).toLowerCase().includes(kw)) return
      if (hit.missing) {
        sums.missing++
        rows.push({kind: 'missing', t, badge: {cls: 'b-missing', txt: '缺整表'}, applied: !!applied[c + '|' + t]})
        return
      }
      const s = hit.st
      if (!s.add && !s.drop && !s.alt && !s.pk) return
      sums.add += s.add; sums.drop += s.drop; sums.alt += s.alt; sums.pk += s.pk
      // 差异列明细只在有差异的表上 lazy 构建（列索引 O(列数)）
      const cells = []
      const li = colIdxOf(diffBase.value, t), ri = colIdxOf(c, t)
      const empty = { list: [], byUpper: new Map() }
      mergedColsIx(li || empty, ri || empty).forEach(row => {
        const cs = row.cs
        if (cs.kind === 'same') return
        if (cs.kind === 'add') cells.push({cls: 'ov-col-add', mark: '+', text: row.name})
        else if (cs.kind === 'drop') cells.push({cls: 'ov-col-drop', mark: '−', text: row.name})
        else cells.push({cls: 'ov-col-alt', mark: '≈', text: row.name + (cs.kind === 'pk' ? '(主键)' : '')})
      })
      rows.push({kind: 'row', t, badge: badgeFromStat(s), applied: !!applied[c + '|' + t], cells})
    })
    const clean = !(sums.add || sums.drop || sums.alt || sums.pk || sums.missing)
    return {id: c, db, ls: {...sums, clean}, rows}
  }).filter(Boolean)
})

/* ---------------- 分组 / 展示（类型显示名统一走 dbRegistry） ---------------- */
const baseType = computed(() => {
  const c = diffBase.value != null ? connOf(diffBase.value) : null
  return c ? c.dbType : ''
})
/** 比对库勾选区：按 dbType 分组，排除基准库。 */
const dbTypeGroups = computed(() => {
  const map = new Map()
  props.connections.forEach(c => {
    if (c.id === diffBase.value) return
    if (!map.has(c.dbType)) map.set(c.dbType, [])
    map.get(c.dbType).push(c)
  })
  return [...map.entries()].map(([type, list]) => ({type, typeName: typeLabel(type), list}))
})
/** 基准库下拉：按 dbType 分组全部连接（失败库 disabled）。 */
const baseSelectGroups = computed(() => {
  const map = new Map()
  props.connections.forEach(c => {
    if (!map.has(c.dbType)) map.set(c.dbType, [])
    map.get(c.dbType).push(c)
  })
  return [...map.entries()].map(([type, list]) => ({type, typeName: typeLabel(type), list}))
})
function fmtFetchedAt(id) {
  const lib = libs[id]
  if (!lib) return ''
  if (lib.error) return '抓取失败'
  if (!lib.fetchedAt) return ''
  return new Date(lib.fetchedAt).toLocaleTimeString('zh-CN', {hour12: false})
}
const fetchStamp = computed(() => {
  if (fetching.value) return '正在抓取快照…'
  // 按「基准+已勾选比对库」范围统计：已有快照 N / 共 M；未拉取的明确提示等手动点击
  const scope = []
  if (diffBase.value != null) scope.push(diffBase.value)
  diffCmps.value.forEach(id => { if (!scope.includes(id)) scope.push(id) })
  if (!scope.length) return '尚未拉取快照'
  const got = scope.filter(hasSnap)
  if (!got.length) return '尚未拉取快照 —— 选好库后点「▶ 拉取快照」'
  if (got.length < scope.length) {
    const pending = scope.filter(id => !hasSnap(id)).map(libName)
    return `快照 ${got.length}/${scope.length} 库 · 待拉取：${pending.join('、')}（点 ▶ 拉取快照）`
  }
  const at = new Date(Math.max(...scope.map(id => libs[id].fetchedAt)))
  return `快照 ${scope.length} 库 · ${at.toLocaleTimeString('zh-CN', {hour12: false})}`
})
/* Esc 关闭（× / Esc 是唯一关闭途径；guard 打开时 Esc 先关 guard，补丁弹窗打开时先关弹窗） */
onMounted(() => window.addEventListener('keydown', onEsc))
onBeforeUnmount(() => window.removeEventListener('keydown', onEsc))
</script>

<template>
  <div v-if="show" class="modal-mask diff-mask">
    <section class="modal diff-modal">
      <header class="modal-header">
        <h2>⇆ 字段差异对比</h2>
        <div class="diff-view-tabs">
          <button type="button" class="view-tab" :class="{ active: diffMode === 'summary' }" @click="setDiffMode('summary')">差异总览</button>
          <button type="button" class="view-tab" :class="{ active: diffMode === 'detail' }" @click="setDiffMode('detail')">单表核对</button>
        </div>
        <div class="diff-summary" :title="summaryText">{{ summaryText }}</div>
        <button type="button" class="icon-btn" @click="emit('close')">×</button>
      </header>

      <div class="diff-toolbar">
        <span class="label">基准库</span>
        <select class="diff-select" :value="diffBase != null ? String(diffBase) : ''" :disabled="fetching" @change="onBaseChange($event)">
          <option value="" disabled>— 选择基准库 —</option>
          <optgroup v-for="g in baseSelectGroups" :key="g.type" :label="g.typeName">
            <option v-for="c in g.list" :key="c.id" :value="String(c.id)" :disabled="!!libs[c.id]?.error">
              {{ c.regionName }}<template v-if="libs[c.id]?.error">（抓取失败）</template>
            </option>
          </optgroup>
        </select>
        <span class="label">比对库（可多选）</span>
        <div class="diff-cmp-list">
          <span v-for="g in dbTypeGroups" :key="'g' + g.type" class="diff-cmp-group">
            <b>{{ g.typeName }}</b>
            <label v-for="c in g.list" :key="c.id" :class="{ cross: c.dbType !== baseType, 'failed-lib': !!libs[c.id]?.error }"
                   :title="libs[c.id]?.error ? '快照失败：' + libs[c.id].error : (c.dbType !== baseType ? '跨方言库：补丁保持 MySQL 风格，执行时经 SQL 转换器翻译' : '')">
              <input type="checkbox" :disabled="fetching || !!libs[c.id]?.error" :checked="diffCmps.includes(c.id)"
                     @change="toggleCmp(c.id, $event.target.checked)">
              {{ c.regionName }}<span v-if="libs[c.id]?.error" class="fail-mark">✗</span>
              <span v-else-if="!hasSnap(c.id)" class="pend-mark" title="尚未拉取该库快照：勾选后点「▶ 拉取快照」抓取结构">待拉取</span>
              <!-- 顶部对比库勾选区不再渲染「缺N 多N」徽章（明细见下方总览卡 / 单表核对 chips / 左栏表名徽章），cmpMini 函数保留以备恢复 -->
            </label>
          </span>
          <span v-if="!dbTypeGroups.length" class="muted">无其他库可选</span>
        </div>
        <button type="button" class="btn small snap-btn" :disabled="!fetchReady || fetching"
                :title="fetchReady ? '' : '先选择基准库并勾选比对库'" @click="fetchAll(false)">
          {{ fetching ? '抓取中…' : '▶ 拉取快照' }}
        </button>
        <button type="button" class="reset-link" :disabled="fetching" :title="'跳过缓存强制重抓当前勾选库'"
                @click="fetchAll(true)">⟳ 强制重抓</button>
        <span class="snap-stamp">{{ fetchStamp }}</span>
        <button type="button" class="btn small patch-entry" :class="{ has: !!patchBadge }" :disabled="!patchEntryEnabled"
                :title="patchEntryEnabled ? '打开补丁工作台：各目标库脚本已分桶累积，可逐库或统一执行（含执行状态）' : '先生成补丁（单表核对 / 自动补齐缺列 / 缺表建表）后这里会显示行数'"
                @click="openPatch()">
          📋 补丁脚本<span v-if="patchBadge" class="patch-entry-cnt">{{ patchBadge }}</span>
        </button>
        <button type="button" class="reset-link" @click="clearAll"
                title="复位对比会话：撤销「已生成补丁/✓已应用」标记、清空全部待执行补丁、清空所选基准/比对库与差异表列表；不影响已执行到库的改动">↺ 撤销「已应用」</button>
      </div>

      <div class="diff-main">
        <aside class="diff-side">
          <div class="diff-side-tools">
            <input v-model="search" class="diff-search" placeholder="搜表名…">
            <label class="diff-only"><input v-model="onlyDiff" type="checkbox">仅差异表</label>
          </div>
          <div class="diff-table-list">
            <template v-for="row in filteredTables" :key="row.t">
              <div class="diff-table-item" :class="{ active: diffTable === row.t && diffMode === 'detail' }" :title="row.badge.title || ''" @click="selectTable(row.t)">
                <span class="tn">{{ row.t }}</span>
                <span class="diff-tbadge" :class="row.badge.cls">{{ row.badge.txt }}</span>
              </div>
            </template>
            <div v-if="diffBase == null" class="ov-empty">未选择基准库 —— 从上方选好后对比</div>
            <div v-else-if="!okLibs.length" class="ov-empty">未拉取快照 —— 选好基准与比对库后点「▶ 拉取快照」</div>
            <div v-else-if="!filteredTables.length" class="ov-empty">无匹配表</div>
          </div>
          <div class="diff-legend">
            <span class="lg add">缺列·可自动补</span>
            <span class="lg drop">多列·默认不删</span>
            <span class="lg type">类型/约束不同·人工定</span>
            <span class="lg pk">主键不同·不自动改</span>
          </div>
        </aside>

        <div class="diff-detail">
          <!-- ============ 差异总览 ============ -->
          <div v-show="diffMode === 'summary'" class="diff-view">
            <div class="diff-ov-hint">核对方向：基准库 → 比对库。安全默认：<b>只自动补「缺列」</b>；多列删除、类型/约束改写、主键差异均需人工确认。生成只累积不打断：脚本按<b>目标库分桶</b>（各比对库 + 基准库），点「📋 补丁脚本」查看每库脚本并逐库 / 统一执行，执行状态就地可见。跨方言库的补丁保持 MySQL 风格，执行时经 SQL 转换器翻译（同型直发）。</div>
            <div class="diff-ov-cards">
              <div v-if="!diffCmps.length" class="ov-empty">请至少勾选一个比对库</div>
              <article v-for="card in ovCards" :key="card.id" class="ov-card">
                <div class="ov-card-head">
                  <span class="nm">{{ card.db.name }}</span>
                  <span class="ty">{{ typeLabel(card.db.dbType) }}{{ card.db.dbType !== baseType ? ' · 跨方言' : '' }}</span>
                  <span v-if="card.pending" class="st pending">待拉取快照</span>
                  <template v-else-if="card.ls">
                    <span v-if="card.ls.clean" class="st clean">✓ 与基准一致</span>
                    <template v-else>
                      <span v-if="card.ls.add" class="st add">缺 {{ card.ls.add }}</span>
                      <span v-if="card.ls.drop" class="st drop">多 {{ card.ls.drop }}</span>
                      <span v-if="card.ls.alt" class="st alt">核对 {{ card.ls.alt }}</span>
                      <span v-if="card.ls.pk" class="st pk">主键 {{ card.ls.pk }}</span>
                      <span v-if="card.ls.missing" class="st drop">缺表 {{ card.ls.missing }}</span>
                    </template>
                  </template>
                  <span class="ov-card-actions">
                    <template v-if="card.pending">
                      <button type="button" class="btn small" @click="fetchAll(false)">▶ 拉取快照</button>
                    </template>
                    <template v-else>
                      <button v-if="card.ls.add && !kwActive" type="button" class="btn small" @click="genLibAdds(card.id)">⊕ 自动补齐缺列</button>
                      <button v-if="card.ls.missing && !kwActive" type="button" class="btn small" @click="genMissingTables(card.id)">⊕ 补 {{ card.ls.missing }} 个缺表建表</button>
                      <button type="button" class="btn small" @click="goCmpDetail(card.id)">单表核对 →</button>
                    </template>
                  </span>
                </div>
                <div v-if="card.pending" class="ov-empty">该库尚未拉取快照 —— 点「▶ 拉取快照」抓取结构后再比对</div>
                <table v-else-if="!card.ls.clean" class="ov-table">
                  <tbody>
                    <tr v-for="(row, i) in card.rows" :key="i" style="cursor:pointer"
                        @click="row.kind === 'row' && goCmpTable(card.id, row.t)">
                      <td class="ov-tbl-name">{{ row.t }}</td>
                      <td>
                        <span v-if="row.kind === 'missing'" class="ov-badge b-missing">缺整表</span>
                        <span v-else class="ov-badge" :class="row.badge.cls">{{ row.badge.txt }}</span>
                        <span v-if="row.applied" class="ov-badge zero" title="本地标记：已为该表生成补丁；点「↺ 撤销「已应用」」清除（不影响已执行到库的改动）">✓ 已应用</span>
                        <div v-if="row.kind === 'missing'" class="ov-cols ov-cols-missing">
                          <button type="button" class="btn small" @click.stop="genOneCreate(card.id, row.t)">⊕ 生成建表脚本</button>
                          <span class="muted">按基准列定义生成 CREATE TABLE 并入补丁，执行时自动转换目标方言</span>
                        </div>
                        <div v-else class="ov-cols">
                          <span v-for="(cell, j) in row.cells" :key="j" :class="cell.cls">{{ cell.mark }} {{ cell.text }}</span>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
                <div v-else-if="kwActive" class="ov-empty">无匹配表</div>
                <div v-else class="ov-empty">全部共有表字段一致</div>
              </article>
            </div>
          </div>

          <!-- ============ 单表核对 ============ -->
          <div v-show="diffMode === 'detail'" class="diff-view">
            <div class="diff-cmp-pills">
              <span class="pill-label">比对库:</span>
              <button v-for="v in cmpPillViews" :key="v.id" type="button" class="diff-cmp-pill"
                      :class="{ active: v.id === diffCmp }" :title="v.title" @click="selectCmp(v.id)">
                {{ v.name }}
              </button>
              <span v-if="!cmpPillViews.length" class="muted">无</span>
              <label class="diff-only d-only" title="隐藏字段类型/约束完全一致的行，只看有差异的字段">
                <input v-model="detailOnlyDiff" type="checkbox">仅差异字段
              </label>
            </div>
            <template v-if="detailReady && diffTable">
              <div class="diff-wrap">
                <div class="diff-head">
                  <div>{{ libName(diffBase) }}（基准）</div>
                  <div class="diff-head-mid">
                    <label class="diff-allchk" :class="{ some: allChkState.indeterminate }"
                           title="一键确认：勾选 = 本表全部差异动作按「对齐基准库」一次性选中（缺列→ADD、多列→DROP、需核对→MODIFY；已勾的「补基准库」方向会被覆盖）。适合差异字段很多的表，全选后请逐行人工核对，再点底部「✓ 生成选中动作」。取消 = 全部不勾。主键差异(pk)不参与自动生成。">
                      <input ref="allChkEl" type="checkbox" :checked="allChkState.checked"
                             @change="toggleAllRows($event.target.checked)">一键确认
                    </label>
                    <span>同步动作 · 逐列确认</span>
                  </div>
                  <div>{{ libName(diffCmp) }}（比对）</div>
                </div>
                <div class="diff-body">
                  <!-- 缺整表分支 -->
                  <div v-if="detailMissingTable" class="ov-empty missing-table">
                    <div class="mt-title">缺整表 · 比对库没有「{{ diffTable }}」</div>
                    <div class="mt-desc">{{ libName(diffCmp) }} 中不存在该表，无法按列生成 ADD / MODIFY（列级补丁需表已存在）。可一键按基准列定义生成 CREATE TABLE 补丁（执行时自动转换目标方言），也可用「SQL 转换」自行处理。</div>
                    <div class="mt-preview">基准字段 {{ fieldsOf(diffBase, diffTable).length }} 个：{{ (fieldsOf(diffBase, diffTable).slice(0, 8).map(f => f.name).join('、')) + (fieldsOf(diffBase, diffTable).length > 8 ? ' …' : '') }}</div>
                    <div class="mt-actions">
                      <button type="button" class="btn small primary" :disabled="detailCreateDone"
                              @click="genOneCreate(diffCmp, diffTable)">
                        {{ detailCreateDone ? '✓ 已生成建表脚本' : '⊕ 生成建表脚本（CREATE TABLE）' }}
                      </button>
                      <span class="muted">并入「比对补丁」（目标库：{{ libName(diffCmp) }}）</span>
                    </div>
                  </div>
                  <!-- 逐列（行已拆子组件 DiffRow：勾选态读 diffState[stateKey]，
                        依赖收集仅在本行，勾选不重建整表） -->
                  <template v-else>
                    <DiffRow v-for="row in detailRows" :key="row.name" :row="row" :state="diffState"
                             @set-choice="onRowChoice" />
                  </template>
                </div>
              </div>
              <div class="diff-actions-bar">
                <template v-if="detailStat.missing">
                  <span class="diff-statline"><b>{{ diffTable }}</b>：<span class="missing-inline">比对库缺整表</span>（{{ libName(diffCmp) }} 无此表，无字段可对齐）</span>
                  <span v-if="detailCreateDone" class="ov-badge zero" title="已为该缺表生成 CREATE TABLE 并入比对补丁；可点上方「补丁脚本」查看/复制/执行">✓ 已生成建表脚本</span>
                </template>
                <template v-else>
                  <span class="diff-statline"><b>{{ diffTable }}</b>：缺 {{ detailStat.add }} · 多 {{ detailStat.drop }} · 需核对 {{ detailStat.alt }} · 主键差异 {{ detailStat.pk }}</span>
                  <button type="button" class="btn primary" @click="genDetail()">✓ 生成选中动作</button>
                </template>
              </div>
            </template>
            <div v-else-if="!detailReady && okLibs.length" class="ov-empty" style="padding:60px 0">所选比对库尚无快照 —— 在左栏勾选后点「▶ 拉取快照」</div>
          </div>
        </div>
      </div>

    </section>
  </div>

  <DiffPatchDialog :show="patchOpen" :items="patchViews" :active="activeBucketView" :busy="anyRunning"
                   @close="closePatch" @select="selectBucket" @input="onPatchInput"
                   @clear="clearCurPatch" @copy="copyCurPatch" @run="runCurPatch" @run-all="runAllPending"/>
  <ConfirmDialog mode="generic" title="补丁操作确认" :message="guard.message" :confirm-text="guard.okText"
                 :danger="guard.danger" :show="guard.show" @confirm="guardOk" @cancel="guardCancel"/>
</template>

<script>
// 顶层仅做组件级分组计算所需的 dbType 展示映射（独立于组合式状态）
export default { name: 'SchemaDiffDialog' }
</script>

<style scoped>
.diff-mask { z-index: 40; }
/* 固定高度：弹窗大小恒定，不随内容多少（选库/拉快照/点表/切视图）来回变高变矮；
   外层不再整体滚动，超高内容在左列表 / 总览卡 / 逐列核对各自分区内滚动 */
.diff-modal {
  width: min(1340px, 100%);
  height: min(86vh, 960px);
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.diff-modal .modal-header { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; justify-content: flex-start; flex: 0 0 auto; background: var(--surface); z-index: 2; padding-bottom: 8px; }
.diff-view-tabs { display: inline-flex; background: var(--ink-50); border-radius: var(--r-pill); padding: 3px; border: 1px solid var(--ink-100); }
.view-tab { border: 0; background: transparent; padding: 5px 14px; border-radius: var(--r-pill); font-size: 12.5px; font-weight: 700; color: var(--ink-500); cursor: pointer; transition: all var(--t-fast) var(--ease); }
.view-tab.active { background: var(--surface); color: var(--mint-700); box-shadow: 0 1px 4px rgba(0, 0, 0, .08); }
.diff-summary { margin-left: auto; font-size: 12px; color: var(--ink-500); background: var(--cream-50); border: 1px dashed var(--cream-200); padding: 4px 12px; border-radius: var(--r-pill); max-width: 45%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.diff-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 12px 0 10px; flex: 0 0 auto; }
.diff-toolbar .label { font-size: 12px; color: var(--ink-500); font-weight: 700; flex: 0 0 auto; }
.diff-toolbar select { border: 1.5px solid var(--ink-100); border-radius: 8px; padding: 6px 10px; background: var(--surface); color: var(--ink-900); font-size: 12.5px; }
.diff-select { min-width: 150px; }
.diff-cmp-list { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.diff-cmp-group { display: flex; align-items: center; gap: 2px; background: var(--ink-50); border: 1px solid var(--ink-100); border-radius: var(--r-pill); padding: 3px 5px 3px 10px; }
.diff-cmp-group b { font-size: 11px; color: var(--ink-500); font-weight: 700; margin-right: 2px; }
.diff-cmp-group label { display: flex; align-items: center; gap: 3px; font-size: 12px; color: var(--ink-700); padding: 2px 8px; border-radius: var(--r-pill); cursor: pointer; }
.diff-cmp-group label:hover { background: var(--mint-100); }
.diff-cmp-group label input { accent-color: var(--mint-500); margin: 0; }
.diff-cmp-group label.cross { color: var(--ink-300); font-style: italic; }
.diff-cmp-group label.failed-lib { color: var(--danger); text-decoration: line-through; cursor: not-allowed; }
.fail-mark { color: var(--danger); font-weight: 800; }
/* 比对库勾选 label 上的该库 vs 基准 微型差异徽章 */
.cmp-mini { font-size: 10px; font-weight: 800; border-radius: var(--r-pill); padding: 0 6px; line-height: 15px; white-space: nowrap; }
.cmp-mini.diff { background: #FDEBD9; color: #A76A12; }
.cmp-mini.ok { background: var(--mint-100); color: var(--mint-700); }
.pend-mark { font-size: 10px; font-weight: 800; color: #A76A12; background: #FDEBD9; border-radius: var(--r-pill); padding: 0 6px; line-height: 15px; }
.snap-btn { margin-left: auto; }
.snap-stamp { font-size: 11px; color: var(--ink-300); }
.patch-entry { display: inline-flex; align-items: center; gap: 6px; }
.patch-entry.has { border-color: var(--cream-300, #EAC98A); background: linear-gradient(180deg, #FFFDF7, #FFF6E0); color: #8A6210; }
.patch-entry.has:hover { border-color: #D9B25C; }
.patch-entry-cnt { font-size: 10.5px; font-weight: 800; background: var(--cream-200); color: #8A6210; border-radius: var(--r-pill); padding: 0 8px; line-height: 16px; }
.reset-link { background: none; border: 0; color: var(--mint-600); font-size: 12px; cursor: pointer; font-weight: 600; }
.reset-link:hover { text-decoration: underline; }

.diff-main { display: grid; grid-template-columns: 232px 1fr; grid-template-rows: minmax(0, 1fr); gap: 12px; flex: 1 1 auto; min-height: 0; overflow: hidden; }
.diff-side { display: flex; flex-direction: column; gap: 8px; min-width: 0; min-height: 0; }
.diff-side-tools { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; flex: 0 0 auto; }
.diff-search { flex: 1 1 130px; border: 1.5px solid var(--ink-100); border-radius: 8px; padding: 6px 10px; font-size: 12px; outline: none; min-width: 80px; }
.diff-search:focus { border-color: var(--mint-300); }
.diff-only { display: inline-flex; align-items: center; gap: 4px; font-size: 11.5px; color: var(--ink-500); cursor: pointer; }
.diff-only input { accent-color: var(--mint-500); margin: 0; }
.diff-detail { min-height: 0; display: flex; flex-direction: column; }
.diff-table-list { border: 1px solid var(--ink-100); border-radius: 10px; overflow: auto; background: var(--surface); flex: 1 1 auto; min-height: 0; }
.diff-table-item { display: flex; justify-content: space-between; align-items: center; gap: 10px; padding: 7px 10px; border-bottom: 1px solid var(--ink-100); font-size: 12px; cursor: pointer; color: var(--ink-700); font-family: var(--font-mono); }
.diff-table-item:last-child { border-bottom: 0; }
.diff-table-item:hover { background: var(--mint-50); }
.diff-table-item.active { background: var(--mint-100); color: var(--mint-700); font-weight: 700; }
.diff-table-item .tn { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.diff-tbadge { font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: var(--r-pill); flex: 0 0 auto; white-space: nowrap; }
.diff-tbadge.ok { background: var(--mint-100); color: var(--mint-600); }
.diff-tbadge.add { background: #FDEBD9; color: #A76A12; }
.diff-tbadge.drop { background: #FFE4EC; color: #C2525F; }
.diff-tbadge.alt { background: #E3EEFB; color: #3E6DA8; }
.diff-tbadge.pk { background: #F1E7F7; color: #83539E; }
.diff-tbadge.missing { background: #F5E3E0; color: #A84A3F; }
.diff-tbadge.applied { background: var(--mint-400); color: #fff; }
.diff-legend { display: flex; flex-wrap: wrap; gap: 4px 12px; font-size: 10.5px; color: var(--ink-500); flex: 0 0 auto; }
.lg { display: inline-flex; align-items: center; gap: 4px; }
.lg::before { content: ''; width: 9px; height: 9px; border-radius: 3px; }
.lg.add::before { background: #F2B65A; }
.lg.drop::before { background: #F08585; }
.lg.type::before { background: #7FB2E5; }
.lg.pk::before { background: #B98FD6; }

.diff-view[hidden] { display: none !important; }
/* 两个视图（总览/单表核对）共撑满右侧剩余高度，内容超高在各自滚动区滚动 */
.diff-view { flex: 1 1 auto; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.diff-view > .ov-empty { flex: 1; display: grid; place-items: center; }
.diff-ov-hint { font-size: 11.5px; line-height: 1.7; color: var(--ink-500); background: var(--cream-50); border: 1px dashed var(--cream-200); border-radius: 8px; padding: 7px 12px; margin-bottom: 10px; flex: 0 0 auto; }
.diff-ov-hint b { color: var(--mint-700); }
.diff-ov-cards { display: flex; flex-direction: column; gap: 10px; flex: 1 1 auto; min-height: 0; overflow: auto; padding-right: 2px; }
.diff-ov-cards > .ov-empty { flex: 1; display: grid; place-items: center; min-height: 120px; }
/* 卡片禁止 flex 收缩：overflow:hidden 会让 min-height:auto 解析为 0，卡片会被滚动容器
 * 无限压扁裁掉内容（521 行缺表/差异被压成容器高度，scrollHeight 恒等于 clientHeight → 无法滚动）。 */
.ov-card { border: 1px solid var(--ink-100); border-radius: 12px; background: var(--surface); overflow: hidden; flex: 0 0 auto; }
.ov-card-head { display: flex; align-items: center; gap: 10px; padding: 9px 12px; background: linear-gradient(180deg, var(--cream-50), var(--cream-100)); border-bottom: 1px solid var(--ink-100); flex-wrap: wrap; }
.ov-card-head .nm { font-weight: 800; color: var(--ink-900); }
.ov-card-head .ty { font-size: 10.5px; color: var(--ink-500); border: 1px solid var(--ink-200); border-radius: 4px; padding: 0 6px; }
.ov-card-head .st { font-size: 11.5px; font-weight: 700; padding: 1px 9px; border-radius: var(--r-pill); }
.ov-card-head .st.add { background: #FDEBD9; color: #A76A12; }
.ov-card-head .st.drop { background: #FFE4EC; color: #C2525F; }
.ov-card-head .st.alt { background: #E3EEFB; color: #3E6DA8; }
.ov-card-head .st.pk { background: #F1E7F7; color: #83539E; }
.ov-card-head .st.clean { background: var(--mint-100); color: var(--mint-700); }
.ov-card-head .st.pending { background: #FDEBD9; color: #A76A12; }
.ov-card-actions { margin-left: auto; display: flex; gap: 6px; }
.ov-table { width: 100%; border-collapse: collapse; }
.ov-table td { padding: 6px 10px; font-size: 12px; vertical-align: top; border-bottom: 1px dashed var(--ink-100); }
.ov-table tr:last-child td { border-bottom: 0; }
.ov-tbl-name { font-family: var(--font-mono); font-weight: 700; color: var(--ink-900); white-space: nowrap; }
.ov-badge { font-size: 11px; font-weight: 800; padding: 0 6px; border-radius: 4px; line-height: 16px; }
.ov-badge.zero { color: var(--mint-600); background: var(--mint-50); }
.ov-badge.b-add { color: #A76A12; background: #FDEBD9; }
.ov-badge.b-drop { color: #C2525F; background: #FFE4EC; }
.ov-badge.b-alt { color: #3E6DA8; background: #E3EEFB; }
.ov-badge.b-pk { color: #83539E; background: #F1E7F7; }
.ov-badge.b-missing { color: #A84A3F; background: #F5E3E0; }
.ov-cols { font-size: 11px; color: var(--ink-500); font-family: var(--font-mono); line-height: 1.7; }
.ov-cols-missing { display: flex; align-items: center; gap: 8px; font-family: inherit; margin-top: 2px; flex-wrap: wrap; }
.ov-col-add { color: #A76A12; }
.ov-col-drop { color: #C2525F; }
.ov-col-alt { color: #3E6DA8; }
.ov-empty { padding: 18px; text-align: center; color: var(--ink-300); font-size: 12px; }
.missing-table .mt-title { font-size: 14px; color: #A84A3F; font-weight: 800; margin-bottom: 10px; }
.missing-table .mt-desc { font-size: 12px; color: var(--ink-500); line-height: 1.8; }
.missing-table .mt-preview { font-size: 11px; color: var(--ink-300); margin-top: 10px; font-family: var(--font-mono); }
.missing-table .mt-actions { display: flex; align-items: center; gap: 10px; justify-content: center; margin-top: 14px; }
.missing-table .mt-actions .muted { font-size: 11px; }
.missing-table .mt-actions .btn.primary { margin-left: 0; }

.diff-cmp-pills { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; margin-bottom: 8px; flex: 0 0 auto; }
.diff-cmp-pill { border: 1.5px solid var(--ink-100); border-radius: var(--r-pill); padding: 3px 12px; font-size: 12px; font-weight: 700; background: var(--surface); color: var(--ink-500); cursor: pointer; }
.diff-cmp-pill.active { background: var(--mint-100); border-color: var(--mint-300); color: var(--mint-700); }
.pill-label { font-size: 12px; color: var(--ink-500); font-weight: 700; }
.d-only { margin-left: auto; }
.diff-wrap { border: 1px solid var(--ink-100); border-radius: 10px; overflow: hidden; background: var(--surface); display: flex; flex-direction: column; flex: 1 1 auto; min-height: 0; }
.diff-head { display: grid; grid-template-columns: 1fr 190px 1fr; background: linear-gradient(180deg, var(--cream-50), var(--cream-100)); border-bottom: 1.5px solid var(--mint-200); flex: 0 0 auto; }
.diff-head > div { padding: 8px 12px; font-size: 12.5px; font-weight: 800; color: var(--ink-700); text-align: center; border-left: 1px solid var(--cream-200); }
.diff-head > div:first-child { border-left: 0; }
.diff-body { flex: 1 1 auto; min-height: 0; overflow: auto; }
/* .diff-row / .diff-cell 等单行样式已迁入 DiffRow.vue（scoped 隔离） */
.diff-actions-bar { display: flex; align-items: center; gap: 8px; margin-top: 10px; flex-wrap: wrap; flex: 0 0 auto; }
.diff-statline { font-size: 11.5px; color: var(--ink-500); }
.missing-inline { color: #A84A3F; font-weight: 800; }
.diff-actions-bar .btn.primary { margin-left: auto; }

/* 一键确认（同步动作 · 逐列确认 总开关）：表头中间列纵向堆叠 + 胶囊勾选 */
.diff-head-mid { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px; line-height: 1.3; }
.diff-head-mid > span { font-size: 12px; }
.diff-allchk { display: inline-flex; align-items: center; gap: 5px; font-size: 11px; font-weight: 800;
  color: var(--ink-700); cursor: pointer; user-select: none;
  background: var(--surface); border: 1px solid var(--mint-200);
  border-radius: var(--r-pill); padding: 1px 10px; line-height: 1.6; white-space: nowrap; }
.diff-allchk:hover { border-color: var(--mint-400); }
.diff-allchk.some { border-style: dashed; border-color: var(--mint-500); background: var(--mint-50); }
.diff-allchk input { accent-color: var(--mint-500); margin: 0; }

.danger-ghost { color: var(--danger); border-color: var(--danger); background: transparent; }
</style>
