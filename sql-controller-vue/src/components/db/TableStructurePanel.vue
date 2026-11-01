<script setup>
/**
 * 表结构面板：只读查看 + 就地编辑（P3）。
 *
 * 编辑态的做法：字段表直接改（类型/可空/默认值/注释），新增/删除字段与索引，
 * 每次改动都请后端生成 DDL 并实时显示；DDL 可手改，执行前弹确认框。
 * DDL 生成放在后端（DbAlterBuilder），因为 MySQL / 达梦 / 神通三种语法差异很大。
 */
import { computed, ref, watch } from 'vue'
import { api } from '../../api'
import ConfirmDialog from '../ConfirmDialog.vue'

const props = defineProps({
  tab: {type: Object, required: true},
  online: {type: Boolean, default: false}
})
const emit = defineEmits(['refresh'])

const MAX_PREVIEW_DEBOUNCE = 400

const copied = ref(false)
const editing = ref(false)
const draft = ref([])
const indexDraft = ref([])
const ddlText = ref('')
const ddlWarnings = ref([])
const previewLoading = ref(false)
const executing = ref(false)
const message = ref('')
const handEdited = ref(false)
const confirm = ref({show: false, sql: '', targets: []})
let previewTimer = null

const structure = computed(() => props.tab.structure)
const columns = computed(() => structure.value?.columns || [])
const indexes = computed(() => structure.value?.indexes || [])
const primaryKeys = computed(() => structure.value?.primaryKeys || [])
const ddlSource = computed(() => structure.value?.ddlSource === 'native' ? '库原生 DDL' : '按结构拼装（仅供参考）')
const canEdit = computed(() => props.online ? !!props.tab.onlineWrite : !!props.tab.writeEnabled)

// ==================== 只读 ====================

async function copyDdl() {
  const text = structure.value?.ddl || ''
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
    setTimeout(() => (copied.value = false), 1600)
  } catch (e) {
    copied.value = false
  }
}

// ==================== 编辑态 ====================

function startEdit() {
  if (!canEdit.value) {
    message.value = props.online ? '线上写入口未开启' : '写入口未开启（sql-controller.write.enabled）'
    return
  }
  draft.value = columns.value.map(column => ({
    name: column.name,
    type: column.type || '',
    nullable: column.nullable !== false,
    defaultValue: column.defaultValue === null || column.defaultValue === undefined ? '' : String(column.defaultValue),
    comment: column.comment || '',
    primaryKey: !!column.primaryKey,
    autoIncrement: !!column.autoIncrement,
    isNew: false
  }))
  indexDraft.value = indexes.value.map(index => ({
    name: index.name,
    columns: [...(index.columns || [])],
    unique: !!index.unique,
    primary: !!index.primary,
    isNew: false
  }))
  ddlText.value = structure.value?.ddl || ''
  ddlWarnings.value = []
  handEdited.value = false
  editing.value = true
  message.value = ''
  // 直接进入编辑时也用一次预览覆盖「按结构拼装」的 CREATE TABLE，避免误以为那是待执行语句
  schedulePreview()
}

function cancelEdit() {
  editing.value = false
  draft.value = []
  indexDraft.value = []
  ddlText.value = ''
  ddlWarnings.value = []
  handEdited.value = false
  message.value = ''
}

function addColumn() {
  draft.value.push({
    name: '', type: 'VARCHAR(64)', nullable: true, defaultValue: '', comment: '',
    primaryKey: false, autoIncrement: false, isNew: true
  })
  schedulePreview()
}

function removeColumn(row) {
  if (row.primaryKey) {
    message.value = '主键字段不能直接删除，请先在库内调整主键'
    return
  }
  const index = draft.value.indexOf(row)
  if (index >= 0) draft.value.splice(index, 1)
  schedulePreview()
}

function addIndex() {
  indexDraft.value.push({name: '', columns: [], unique: false, primary: false, isNew: true})
}

function removeIndex(row) {
  if (row.primary) {
    message.value = '主键索引不能在这里删除'
    return
  }
  const index = indexDraft.value.indexOf(row)
  if (index >= 0) indexDraft.value.splice(index, 1)
  schedulePreview()
}

function toggleIndexColumn(row, name, event) {
  const checked = event.target.checked
  if (checked && !row.columns.includes(name)) {
    row.columns.push(name)
  } else if (!checked) {
    row.columns = row.columns.filter(item => item !== name)
  }
  schedulePreview()
}

/** 对比原始结构 → 操作清单。 */
function buildOps() {
  const ops = []
  const original = new Map(columns.value.map(column => [column.name, column]))
  const seen = new Set()
  for (const row of draft.value) {
    if (row.isNew) {
      if (!row.name.trim()) continue
      ops.push({
        type: 'add', column: row.name.trim(), dataType: row.type,
        nullable: row.nullable, defaultValue: row.defaultValue || null, comment: row.comment || null
      })
      continue
    }
    seen.add(row.name)
    const before = original.get(row.name)
    if (!before) continue
    const typeChanged = String(before.type || '').trim() !== String(row.type || '').trim()
    const nullableChanged = (before.nullable !== false) !== !!row.nullable
    const defaultChanged = String(before.defaultValue ?? '').trim() !== String(row.defaultValue ?? '').trim()
    const commentChanged = String(before.comment || '').trim() !== String(row.comment || '').trim()
    if (typeChanged || nullableChanged || defaultChanged || commentChanged) {
      ops.push({
        type: 'modify', column: row.name, dataType: row.type,
        nullable: row.nullable, defaultValue: row.defaultValue || null, comment: row.comment || null
      })
    }
  }
  for (const column of columns.value) {
    if (!seen.has(column.name)) {
      ops.push({type: 'drop', column: column.name})
    }
  }

  const originalIndexes = new Map(indexes.value.map(index => [index.name, index]))
  const seenIndex = new Set()
  for (const row of indexDraft.value) {
    if (row.primary) continue
    if (row.isNew) {
      if (!row.columns.length) continue
      ops.push({type: 'index-add', name: row.name.trim() || null, columns: [...row.columns], unique: !!row.unique})
      continue
    }
    seenIndex.add(row.name)
    const before = originalIndexes.get(row.name)
    if (!before) continue
    const sameColumns = JSON.stringify(before.columns || []) === JSON.stringify(row.columns)
    if (!sameColumns) {
      // 索引改列 = 先删后建（MySQL 不支持原地改索引列）
      ops.push({type: 'index-drop', name: row.name})
      ops.push({type: 'index-add', name: row.name, columns: [...row.columns], unique: !!row.unique})
    }
  }
  for (const index of indexes.value) {
    if (index.primary) continue
    if (!seenIndex.has(index.name)) {
      ops.push({type: 'index-drop', name: index.name})
    }
  }
  return ops
}

function schedulePreview() {
  if (!editing.value) return
  handEdited.value = false
  clearTimeout(previewTimer)
  previewTimer = setTimeout(runPreview, MAX_PREVIEW_DEBOUNCE)
}

async function runPreview() {
  if (!editing.value) return
  const ops = buildOps()
  if (!ops.length) {
    ddlText.value = ''
    ddlWarnings.value = ['还没有检测到结构改动']
    return
  }
  previewLoading.value = true
  try {
    const body = {connectionId: props.tab.connectionId, table: props.tab.table, ops}
    const data = props.online ? await api.onlineDbStructurePreview(body) : await api.dbStructurePreview(body)
    ddlText.value = (data.statements || []).map(item => item.sql).join(';\n')
    ddlWarnings.value = data.warnings || []
    message.value = ''
  } catch (e) {
    message.value = e.message
    ddlWarnings.value = []
  } finally {
    previewLoading.value = false
  }
}

function onDdlInput() {
  handEdited.value = true
}

function closeConfirm() {
  confirm.value = {show: false, sql: '', targets: []}
}

function askExecute() {
  const sql = ddlText.value.trim()
  if (!sql) {
    message.value = '没有可执行的 DDL'
    return
  }
  confirm.value = {
    show: true,
    sql,
    targets: [{regionName: props.online ? `${props.tab.connectionName}（生产）` : (props.tab.connectionName || '本地')}]
  }
}

async function executeDdl() {
  confirm.value = {show: false, sql: '', targets: []}
  const statements = ddlText.value.split(/;\s*\n/).map(item => item.trim().replace(/;$/, '')).filter(Boolean)
  if (!statements.length) {
    message.value = '没有可执行的 DDL'
    return
  }
  executing.value = true
  message.value = ''
  try {
    const body = {
      connectionId: props.tab.connectionId,
      table: props.tab.table,
      ops: buildOps(),
      statements: statements.map(sql => ({sql})),
      confirmed: true
    }
    const result = props.online ? await api.onlineDbDdl(body) : await api.dbStructureExecute(body)
    if (result.ok) {
      message.value = result.message || '结构变更已执行'
      editing.value = false
      emit('refresh')
    } else {
      message.value = result.message || '结构变更失败'
    }
  } catch (e) {
    message.value = e.message
  } finally {
    executing.value = false
  }
}

watch(() => props.tab.key, () => {
  if (editing.value) cancelEdit()
})

/**
 * 右键「编辑表结构」：表树只能设 tab.editRequest，面板是 v-if 挂载的，
 * 所以这里用 immediate 的 watcher 处理「挂载时请求已经存在」的情况；
 * 结构还没加载完就等它加载完再进编辑态（否则 draft 会是空的）。
 */
function consumeEditRequest() {
  const request = props.tab.editRequest || 0
  if (!request || request <= (props.tab.editHandled || 0)) return
  if (!structure.value) return
  props.tab.editHandled = request
  startEdit()
}

watch(() => [props.tab.editRequest, !!structure.value], consumeEditRequest, {immediate: true})
</script>

<template>
  <div class="db-struct">
    <div v-if="tab.structureLoading" class="command-empty">正在读取表结构…</div>
    <div v-else-if="!structure" class="command-empty">暂无结构信息</div>
    <template v-else>
      <div class="db-struct-bar">
        <span class="db-struct-meta">字段 {{ editing ? draft.length : columns.length }} · 索引
          {{ editing ? indexDraft.length : indexes.length }} · 主键
          {{ (primaryKeys || []).join(', ') || '无' }}</span>
        <span class="db-ddl-source" :class="{native: structure.ddlSource === 'native'}">{{ ddlSource }}</span>
        <span class="db-struct-space"></span>
        <template v-if="!editing">
          <button v-if="canEdit" class="btn small" @click="startEdit">编辑结构</button>
          <button class="btn small" @click="emit('refresh')">重新抓取</button>
        </template>
        <template v-else>
          <button class="link-btn" :disabled="previewLoading" @click="runPreview">重新生成 DDL</button>
          <button class="btn small" @click="cancelEdit">取消</button>
          <button class="btn small danger" :disabled="executing" @click="askExecute">
            {{ executing ? '执行中…' : '执行 DDL' }}
          </button>
        </template>
      </div>

      <div v-if="message" class="table-notice" @click="message = ''">{{ message }}</div>

      <div class="db-struct-scroll">
        <table class="db-struct-table" :class="{'is-editing': editing}">
          <thead>
          <tr>
            <th>字段</th>
            <th>类型</th>
            <th class="center">可空</th>
            <th class="center">主键</th>
            <th class="center">自增</th>
            <th>默认值</th>
            <th>注释</th>
            <th v-if="editing"></th>
          </tr>
          </thead>
          <tbody>
          <template v-if="!editing">
            <tr v-for="column in columns" :key="column.name">
              <td class="name">{{ column.name }}</td>
              <td class="mono">{{ column.type }}</td>
              <td class="center">{{ column.nullable === false ? 'NO' : (column.nullable === true ? 'YES' : '—') }}</td>
              <td class="center"><span v-if="column.primaryKey" class="db-pk">PK</span></td>
              <td class="center"><span v-if="column.autoIncrement" class="db-auto">AI</span></td>
              <td class="mono muted">{{ column.defaultValue === null || column.defaultValue === undefined ? '—' : column.defaultValue }}</td>
              <td class="muted">{{ column.comment || '—' }}</td>
            </tr>
          </template>
          <template v-else>
            <tr v-for="row in draft" :key="row.name + (row.isNew ? '-new' : '')" :class="{'struct-new': row.isNew}">
              <td>
                <input v-model="row.name" class="struct-input name" :disabled="!row.isNew" placeholder="字段名"
                       @input="schedulePreview"/>
              </td>
              <td><input v-model="row.type" class="struct-input mono" placeholder="VARCHAR(64)" @input="schedulePreview"/></td>
              <td class="center"><input v-model="row.nullable" type="checkbox" @change="schedulePreview"/></td>
              <td class="center"><span v-if="row.primaryKey" class="db-pk">PK</span></td>
              <td class="center"><span v-if="row.autoIncrement" class="db-auto">AI</span></td>
              <td><input v-model="row.defaultValue" class="struct-input mono" placeholder="无" @input="schedulePreview"/></td>
              <td><input v-model="row.comment" class="struct-input" placeholder="注释" @input="schedulePreview"/></td>
              <td class="center">
                <button class="row-del" title="删除字段" @click="removeColumn(row)">×</button>
              </td>
            </tr>
            <tr class="struct-add-row">
              <td colspan="8">
                <button class="link-btn" @click="addColumn">+ 新增字段</button>
              </td>
            </tr>
          </template>
          </tbody>
        </table>

        <div class="db-index-block">
          <h4>索引</h4>
          <div v-for="index in (editing ? indexDraft : indexes)" :key="index.name" class="db-index-row">
            <template v-if="editing">
              <input v-if="!index.primary" v-model="index.name" class="struct-input index-name" :disabled="!index.isNew"
                     placeholder="索引名"/>
              <span v-else class="db-index-name">{{ index.name }}</span>
              <span class="db-index-badge" :class="{ unique: index.unique, primary: index.primary }">
                {{ index.primary ? 'PRIMARY' : (index.unique ? 'UNIQUE' : 'INDEX') }}
              </span>
              <template v-if="index.primary">
                <span class="mono muted">{{ (index.columns || []).join(', ') }}</span>
              </template>
              <template v-else>
                <label v-for="column in draft" :key="column.name" class="index-col-chip">
                  <input type="checkbox" :checked="index.columns.includes(column.name)"
                         @change="toggleIndexColumn(index, column.name, $event)"/>
                  {{ column.name }}
                </label>
                <label v-if="index.isNew" class="index-col-chip">
                  <input type="checkbox" :checked="index.unique"
                         @change="index.unique = $event.target.checked; schedulePreview()"/> 唯一
                </label>
                <button v-if="!index.primary" class="row-del" title="删除索引" @click="removeIndex(index)">×</button>
              </template>
            </template>
            <template v-else>
              <span class="db-index-name">{{ index.name }}</span>
              <span class="db-index-badge" :class="{ unique: index.unique, primary: index.primary }">
                {{ index.primary ? 'PRIMARY' : (index.unique ? 'UNIQUE' : 'INDEX') }}
              </span>
              <span class="mono muted">{{ (index.columns || []).join(', ') }}</span>
            </template>
          </div>
          <button v-if="editing" class="link-btn" @click="addIndex">+ 新增索引</button>
        </div>

        <div class="db-ddl-block">
          <div class="db-ddl-head">
            <h4>{{ editing ? '待执行 DDL（可手改）' : 'DDL' }}</h4>
            <button v-if="!editing" class="link-btn" @click="copyDdl">{{ copied ? '已复制' : '复制' }}</button>
            <span v-else-if="previewLoading" class="muted small">生成中…</span>
          </div>
          <textarea v-if="editing" v-model="ddlText" class="db-ddl ddl-edit" spellcheck="false"
                    placeholder="改动字段后这里会实时生成 ALTER 语句" @input="onDdlInput"></textarea>
          <pre v-else class="db-ddl">{{ structure.ddl }}</pre>
          <ul v-if="editing && ddlWarnings.length" class="ddl-warnings">
            <li v-for="(warning, index) in ddlWarnings" :key="index">{{ warning }}</li>
          </ul>
        </div>
      </div>
    </template>

    <ConfirmDialog :show="confirm.show" mode="sql" title="确认执行结构变更"
                   :message="online ? '⚠ 生产环境：结构变更会立即生效且不可回滚' : '结构变更会立即生效，请确认 DDL 无误'"
                   :sql="confirm.sql" :targets="confirm.targets" :danger="online"
                   confirm-text="执行 DDL" @confirm="executeDdl" @cancel="closeConfirm"/>
  </div>
</template>
