<script setup>
/**
 * 表视图外壳（布局 A 的主区域）：标题栏 + 数据/结构子 Tab + 数据网格 + 变更条 + 分页。
 *
 * 数据状态直接挂在 Tab 对象上（state/tableTabs），所以：
 * - 切回 SQL 工作台再切回来，分页/排序/筛选/已加载行/未提交改动都还在；
 * - 多个表 Tab 之间来回切不会重复请求（dataAt 命中即复用）。
 *
 * 线上（env=online）与本地走同一套界面，只在 api 层换前缀：
 * 读走小助手只读通道，写走 SM4 updateSql 单语句通道。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../../api'
import { clearChanges, patchTab } from '../../state/tableTabs'
import { DB_REGISTRY } from '../../utils/dbRegistry'
import { buildSubmitList, dirtySummary, pickPk } from '../../utils/rowDiff'
import ConfirmDialog from '../ConfirmDialog.vue'
import RowEditBar from './RowEditBar.vue'
import TableDataGrid from './TableDataGrid.vue'
import TableStructurePanel from './TableStructurePanel.vue'

const props = defineProps({tab: {type: Object, required: true}})

const tab = computed(() => props.tab)
const online = computed(() => tab.value.env === 'online')
const filterText = ref(props.tab.keyword || '')
const exporting = ref(false)
const submitting = ref(false)
const notice = ref('')
let filterTimer = null
let draftSeq = 0

const confirm = ref({show: false, title: '', message: '', sql: '', targets: []})
const pendingSubmit = ref([])

/** 写开关状态全局只查一次（同一页面多个表 Tab 共用）。 */
let writeStatusPromise = null
function ensureWriteStatus() {
  if (!writeStatusPromise) {
    writeStatusPromise = api.dbWriteStatus().catch(() => ({local: false, online: false}))
  }
  return writeStatusPromise
}

const dbTypeLabel = computed(() => DB_REGISTRY[tab.value.dbType]?.label || tab.value.dbType || '数据库')
const totalPages = computed(() => Math.max(1, Math.ceil(Number(tab.value.total || 0) / Math.max(1, Number(tab.value.pageSize) || 1))))
const canWrite = computed(() => !!tab.value.editable && (online.value ? !!tab.value.onlineWrite : !!tab.value.writeEnabled))
const summary = computed(() => dirtySummary({edits: tab.value.edits, drafts: tab.value.drafts, deletes: tab.value.deletes}))
const readOnlyHint = computed(() => {
  if (!tab.value.editable) return '该表无主键，翻页可能不稳定且不可编辑'
  if (online.value) return '线上写入口未开启（只能看，不能改）'
  return '写入口未开启（只能看，不能改）'
})

function patch(next) {
  patchTab(tab.value.key, next)
}

/* ---------------- 数据 / 结构加载（本地 / 线上同构） ---------------- */

async function loadStructure(force = false) {
  if (tab.value.structureLoading) return
  patch({structureLoading: true, error: ''})
  try {
    const data = online.value
      ? await api.onlineDbTable(tab.value.connectionId, tab.value.table, force)
      : await api.dbTable(tab.value.connectionId, tab.value.table, force)
    patch({structure: data, structureAt: Date.now(), structureCachedAt: data.structureCachedAt || 0})
  } catch (e) {
    patch({error: e.message})
  } finally {
    patch({structureLoading: false})
  }
}

async function loadData() {
  if (tab.value.loading) return
  patch({loading: true, error: ''})
  const body = {
    connectionId: tab.value.connectionId,
    table: tab.value.table,
    page: tab.value.page,
    pageSize: tab.value.pageSize,
    orderBy: tab.value.orderBy,
    keyword: tab.value.keyword
  }
  try {
    const data = online.value ? await api.onlineDbTableData(body) : await api.dbTableData(body)
    patch({
      columns: data.columns || [],
      rows: data.rows || [],
      total: Number(data.total || 0),
      sql: data.sql || '',
      primaryKeys: data.primaryKeys || [],
      editable: !!data.editable,
      unstable: !!data.unstable,
      dataAt: Date.now()
    })
  } catch (e) {
    patch({error: e.message, rows: [], total: 0})
  } finally {
    patch({loading: false})
  }
}

function ensureLoaded() {
  if (!tab.value.structureAt) loadStructure()
  if (!tab.value.dataAt) loadData()
}

function setView(view) {
  if (tab.value.view === view) return
  patch({view})
  if (view === 'struct' && !tab.value.structureAt) loadStructure()
}

function onSort(order) {
  patch({orderBy: order, page: 1})
  loadData()
}

function goto(page) {
  const target = Math.min(Math.max(1, page), totalPages.value)
  if (target === tab.value.page) return
  patch({page: target})
  loadData()
}

function changeSize(size) {
  patch({pageSize: Number(size) || 50, page: 1})
  loadData()
}

function onFilterInput() {
  clearTimeout(filterTimer)
  filterTimer = setTimeout(() => {
    const keyword = filterText.value.trim()
    if (keyword === (tab.value.keyword || '')) return
    patch({keyword, page: 1})
    loadData()
  }, 350)
}

async function refresh() {
  patch({dataAt: 0})
  await loadStructure(true)
  await loadData()
}

async function exportCsv() {
  if (exporting.value) return
  exporting.value = true
  notice.value = ''
  try {
    const body = {
      connectionId: tab.value.connectionId,
      table: tab.value.table,
      keyword: tab.value.keyword,
      orderBy: tab.value.orderBy
    }
    const {blob, filename} = online.value ? await api.onlineDbTableExport(body) : await api.dbTableExport(body)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (e) {
    patch({error: e.message})
  } finally {
    exporting.value = false
  }
}

/* ---------------- 行编辑 ---------------- */

function addRow() {
  if (!canWrite.value) {
    notice.value = readOnlyHint.value
    return
  }
  if (!tab.value.drafts) patch({drafts: []})
  draftSeq += 1
  tab.value.drafts.push({key: `new-${Date.now()}-${draftSeq}`, values: {}})
  patch({view: 'data'})
}

function onDeleteRow(item) {
  if (item.draft) {
    const index = tab.value.drafts.findIndex(draft => draft.key === item.key)
    if (index >= 0) tab.value.drafts.splice(index, 1)
    return
  }
  if (!canWrite.value) {
    notice.value = readOnlyHint.value
    return
  }
  if (!tab.value.deletes) patch({deletes: []})
  if (tab.value.deletes.some(entry => entry.key === item.key)) return
  tab.value.deletes.push({key: item.key, pk: pickPk(item.row, tab.value.primaryKeys)})
  if (tab.value.edits) delete tab.value.edits[item.key]
}

function revert() {
  clearChanges(tab.value)
  notice.value = ''
}

/* ---------------- 提交：dryRun → 确认 → 执行 ---------------- */

async function submit() {
  if (submitting.value) return
  const list = buildSubmitList({
    rows: tab.value.rows,
    edits: tab.value.edits,
    drafts: tab.value.drafts,
    deletes: tab.value.deletes
  }, tab.value.primaryKeys)
  if (!list.length) {
    notice.value = '没有需要提交的改动'
    return
  }
  submitting.value = true
  notice.value = ''
  try {
    const emptyDraft = list.find(item => item.op === 'insert' && !Object.keys(item.values || {}).length)
    if (emptyDraft) {
      notice.value = '新增行还没有填写任何字段，请先双击单元格填入内容'
      return
    }
    const payloads = list.map(item => withOp(item, true))
    const previews = []
    for (const payload of payloads) {
      previews.push(await callWrite(payload))
    }
    pendingSubmit.value = payloads.map(payload => ({...payload, dryRun: false}))
    confirm.value = {
      show: true,
      title: online.value ? '确认修改生产数据' : '确认提交修改',
      message: `${online.value ? '⚠ 生产环境 · ' + (tab.value.connectionName || '') : '本地库 · ' + (tab.value.connectionName || '')}`
        + `　表 ${tab.value.table}　共 ${list.length} 项（${summary.value.description}）`,
      sql: previews.map(item => item.sql).join(';\n'),
      targets: [{regionName: online.value ? `${tab.value.connectionName}（生产）` : (tab.value.connectionName || '本地')}]
    }
  } catch (e) {
    notice.value = e.message
  } finally {
    submitting.value = false
  }
}

async function confirmSubmit() {
  confirm.value.show = false
  submitting.value = true
  const payloads = pendingSubmit.value
  const failures = []
  try {
    for (const payload of payloads) {
      try {
        const result = await callWrite(payload)
        if (!result.ok) {
          failures.push(`${result.kind || '执行'}：${result.message || '未生效'}`)
        }
      } catch (e) {
        failures.push(e.message)
      }
    }
    if (failures.length) {
      notice.value = `有 ${failures.length} 项未成功：${failures.slice(0, 3).join('；')}（改动已保留，可重试或撤销）`
    } else {
      clearChanges(tab.value)
      notice.value = `已提交 ${payloads.length} 项变更`
    }
    await refresh()
  } finally {
    submitting.value = false
    pendingSubmit.value = []
  }
}

function withOp(item, dryRun) {
  const base = {
    connectionId: tab.value.connectionId,
    table: tab.value.table,
    dryRun,
    env: tab.value.env
  }
  if (item.op === 'update') return {...base, pk: item.pk, changes: item.changes}
  if (item.op === 'insert') return {...base, values: item.values}
  return {...base, pk: item.pk}
}

function callWrite(payload) {
  const dryRun = !!payload.dryRun
  if (payload.changes) {
    return online.value ? api.onlineDbRowUpdate(payload) : api.dbRowUpdate(payload)
  }
  if (payload.values) {
    return online.value ? api.onlineDbRowInsert(payload) : api.dbRowInsert(payload)
  }
  return online.value ? api.onlineDbRowDelete(payload) : api.dbRowDelete(payload)
}

function onNeedWrite(message) {
  notice.value = message
}

function closeConfirm() {
  confirm.value = {...confirm.value, show: false}
}

/* ---------------- 生命周期 ---------------- */

onMounted(async () => {
  const status = await ensureWriteStatus()
  patch({writeEnabled: !!status.local, onlineWrite: !!status.online})
  ensureLoaded()
})

watch(() => tab.value.key, async () => {
  filterText.value = tab.value.keyword || ''
  const status = await ensureWriteStatus()
  patch({writeEnabled: !!status.local, onlineWrite: !!status.online})
  ensureLoaded()
})

/**
 * 外部刷新（左侧表树右键「刷新」/ 本工具 DDL 后失效）：invalidateTab 会递增 refreshToken，
 * 这里负责把已挂载的 Tab 真正重拉一遍——否则后端缓存清了，界面上还是旧结构。
 */
watch(() => tab.value.refreshToken, async value => {
  if (!value) return
  filterText.value = tab.value.keyword || ''
  await loadStructure(true)
  await loadData()
})

/** 结构缓存时间显示：永久缓存下用户必须看得见这份结构有多旧。 */
function fmtCachedAt(ts) {
  if (!ts) return ''
  const date = new Date(Number(ts))
  const pad = value => String(value).padStart(2, '0')
  return `${date.getMonth() + 1}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
</script>

<template>
  <section class="table-workspace">
    <header class="table-head">
      <div class="table-title">
        <span class="table-env" :class="tab.env">{{ tab.env === 'online' ? '线上' : '本地' }}</span>
        <b class="table-name">{{ tab.table }}</b>
        <span class="table-meta">{{ dbTypeLabel }}<template v-if="tab.connectionName"> · {{ tab.connectionName }}</template></span>
        <span v-if="tab.structureCachedAt" class="table-meta table-cached"
              title="结构缓存时间（默认不过期）。结构被外部改动后，右键表名「刷新」或点右上「刷新」">
          结构 {{ fmtCachedAt(tab.structureCachedAt) }}
        </span>
        <span v-if="tab.comment" class="table-comment" :title="tab.comment">{{ tab.comment }}</span>
      </div>
      <div class="table-actions">
        <button v-if="tab.view === 'data' && canWrite" class="btn small" @click="addRow">+ 新增行</button>
        <button class="btn small" :disabled="tab.loading" @click="refresh">刷新</button>
        <button class="btn small" :disabled="exporting" @click="exportCsv">{{ exporting ? '导出中…' : '导出 CSV' }}</button>
      </div>
    </header>

    <div class="table-subtabs">
      <button class="tab" :class="{active: tab.view === 'data'}" @click="setView('data')">数据</button>
      <button class="tab" :class="{active: tab.view === 'struct'}" @click="setView('struct')">结构</button>
      <div v-if="tab.view === 'data'" class="table-toolbar">
        <input v-model="filterText" class="table-filter" placeholder="筛选：任意文本列包含…" spellcheck="false"
               @input="onFilterInput"/>
        <span v-if="tab.unstable" class="table-warn" title="无主键表的翻页顺序由数据库决定，可能重复或漏行">无主键 · 翻页不稳定</span>
        <span v-else-if="!canWrite" class="table-warn" :title="readOnlyHint">只读</span>
        <span v-if="!tab.loading" class="table-count">共 {{ tab.total }} 行</span>
        <span v-else class="table-count">读取中…</span>
      </div>
    </div>

    <div v-if="tab.error" class="table-error">{{ tab.error }}</div>
    <div v-if="notice" class="table-notice" @click="notice = ''">{{ notice }}</div>

    <div class="table-body">
      <TableDataGrid v-if="tab.view === 'data'" :tab="tab" @sort="onSort" @delete="onDeleteRow" @need-write="onNeedWrite"/>
      <TableStructurePanel v-else :tab="tab" :online="online" @refresh="loadStructure(true)"/>
    </div>

    <RowEditBar v-if="tab.view === 'data'" :tab="tab" :submitting="submitting"
                @submit="submit" @revert="revert"/>

    <footer v-if="tab.view === 'data'" class="table-foot">
      <button class="btn small" :disabled="tab.page <= 1 || tab.loading" @click="goto(tab.page - 1)">上一页</button>
      <span class="page-indicator">第 {{ tab.page }} / {{ totalPages }} 页</span>
      <button class="btn small" :disabled="tab.page >= totalPages || tab.loading" @click="goto(tab.page + 1)">下一页</button>
      <label class="table-page-size">每页
        <select :value="tab.pageSize" @change="changeSize($event.target.value)">
          <option :value="20">20</option>
          <option :value="50">50</option>
          <option :value="100">100</option>
        </select> 条
      </label>
      <span class="table-sql" :title="tab.sql">{{ tab.sql }}</span>
    </footer>

    <ConfirmDialog :show="confirm.show" mode="sql" :title="confirm.title" :message="confirm.message"
                   :sql="confirm.sql" :targets="confirm.targets" :danger="online"
                   confirm-text="确认执行" @confirm="confirmSubmit" @cancel="closeConfirm"/>
  </section>
</template>
