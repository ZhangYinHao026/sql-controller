<script setup>
/**
 * 表数据网格：只读浏览 + 就地编辑（P2）。
 *
 * 交互约定：
 * - 单击选行，双击单元格进入编辑（Enter 提交、Esc 取消、失焦即提交）；
 * - 清空输入框 = NULL（列可空时），非空值按原样交给后端按列类型归一；
 * - 改动落在 tab.edits / tab.drafts 上（不直接改 rows），提交由 RowEditBar 统一发起；
 * - 没有主键、写开关关闭、线上未开写权限时，双击不进入编辑并给出原因。
 */
import { computed, ref } from 'vue'
import { cellHint, cellTitle, formatCell, isDateType, isNumericType, rowNumber } from '../../utils/cellFormat'
import { rowKey } from '../../utils/rowDiff'

const props = defineProps({tab: {type: Object, required: true}})
const emit = defineEmits(['sort', 'delete', 'need-write'])

/** 自动聚焦（编辑态输入框用）。 */
const vFocus = {
  mounted: el => {
    el.focus()
    if (el.select) el.select()
  }
}

const selectedRow = ref('')
const editing = ref(null)

const columns = computed(() => props.tab.columns || [])
const primaryKeys = computed(() => props.tab.primaryKeys || [])
const editable = computed(() => {
  const tab = props.tab
  if (!tab.editable) return false
  return tab.env === 'online' ? !!tab.onlineWrite : !!tab.writeEnabled
})

const displayRows = computed(() => {
  const drafts = (props.tab.drafts || []).map(draft => ({key: draft.key, draft, row: null}))
  const rows = (props.tab.rows || []).map(row => ({key: rowKey(row, primaryKeys.value) || '', draft: null, row}))
  return drafts.concat(rows)
})

function cellValue(item, column) {
  if (item.draft) {
    const value = item.draft.values?.[column.name]
    return value === undefined ? null : value
  }
  const edits = props.tab.edits?.[item.key]
  if (edits && Object.prototype.hasOwnProperty.call(edits, column.name)) {
    return edits[column.name]
  }
  return item.row[column.name]
}

function isEditedCell(item, column) {
  if (item.draft) return true
  const edits = props.tab.edits?.[item.key]
  return !!edits && Object.prototype.hasOwnProperty.call(edits, column.name)
}

function cellClass(item, column) {
  const value = cellValue(item, column)
  return {
    'is-number': isNumericType(column.type),
    'is-date': isDateType(column.type),
    'is-null': value === null || value === undefined,
    'is-edited': isEditedCell(item, column)
  }
}

function cellText(item, column) {
  return formatCell(cellValue(item, column), column.type)
}

function sortMark(column) {
  const order = props.tab.orderBy
  if (!order || order.column !== column.name) return ''
  return order.desc ? '↓' : '↑'
}

function sortBy(column) {
  const order = props.tab.orderBy
  if (!order || order.column !== column.name) {
    emit('sort', {column: column.name, desc: false})
  } else if (!order.desc) {
    emit('sort', {column: column.name, desc: true})
  } else {
    emit('sort', null)
  }
}

function selectRow(key) {
  selectedRow.value = selectedRow.value === key ? '' : key
}

/** 双击进入编辑：无主键 / 只读时给出原因，让用户知道为什么点不动。 */
function startEdit(item, column) {
  const readOnlyBlob = /blob|binary|image|bytea/i.test(String(column.type || ''))
  if (!editable.value) {
    emit('need-write', props.tab.env === 'online'
      ? '线上写入口未开启（sql-controller.write.online-enabled）'
      : (props.tab.editable ? '写入口未开启（sql-controller.write.enabled）' : '该表无主键，无法按行编辑'))
    return
  }
  if (readOnlyBlob) {
    emit('need-write', `字段 ${column.name} 是二进制类型，暂不支持在网格里编辑`)
    return
  }
  const raw = cellValue(item, column)
  editing.value = {
    key: item.key,
    column: column.name,
    value: raw === null || raw === undefined ? '' : String(raw),
    // 原值状态：输入框占位符提示当前是 NULL 还是空串（两者进编辑时都是空输入框，肉眼看不出）
    orig: raw === null || raw === undefined ? 'null' : (raw === '' ? 'empty' : 'value')
  }
}

function commit() {
  const state = editing.value
  if (!state) return
  const value = state.value
  const item = displayRows.value.find(row => row.key === state.key)
  const column = columns.value.find(col => col.name === state.column)
  editing.value = null
  if (!item || !column) return
  // 值没变就不记改动，避免「点一下就算脏」
  const before = cellValue(item, column)
  const beforeText = before === null || before === undefined ? '' : String(before)
  if (beforeText === value) return

  // 清空 = NULL（列可空时，不可空列交给数据库报错）；输入 ''（两个单引号）= 空字符串
  // 之前的版本没法把可空列设回空串（清空永远变 NULL），这里补上 Navicat 同款约定
  const normalized = value === "''"
    ? ''
    : (value === '' && column.nullable !== false ? null : value)

  if (item.draft) {
    item.draft.values[state.column] = normalized
    return
  }
  if (!props.tab.edits) props.tab.edits = {}
  if (!props.tab.edits[item.key]) props.tab.edits[item.key] = {}
  props.tab.edits[item.key][state.column] = normalized
}

function cancel() {
  editing.value = null
}

function removeRow(item) {
  emit('delete', item)
}

function rowClass(item) {
  return {
    selected: selectedRow.value === item.key,
    'row-draft': !!item.draft,
    'row-dirty': !item.draft && props.tab.edits?.[item.key] && Object.keys(props.tab.edits[item.key]).length > 0
  }
}

defineExpose({startEdit})
</script>

<template>
  <div class="db-grid-wrap">
    <table class="db-grid">
      <thead>
      <tr>
        <th class="db-grid-no">#</th>
        <th v-for="column in columns" :key="column.name"
            :class="['db-grid-head', {'is-sorted': tab.orderBy && tab.orderBy.column === column.name}]"
            :style="{ minWidth: column.primaryKey ? '110px' : '120px' }"
            :title="`${column.name} · ${column.type || ''}${column.comment ? ' · ' + column.comment : ''}`"
            @click="sortBy(column)">
          <span class="db-col-head">
            <span class="db-col-name">{{ column.name }}</span>
            <span v-if="column.primaryKey" class="db-pk">PK</span>
            <span class="db-sort">{{ sortMark(column) }}</span>
          </span>
          <!-- 列头第二行优先展示注释（比类型更有用），无注释时回落显示类型；完整信息看悬浮 title -->
          <span class="db-col-sub">{{ column.comment || column.type }}</span>
        </th>
        <th v-if="editable" class="db-grid-act"></th>
      </tr>
      </thead>
      <tbody>
      <tr v-for="(item, index) in displayRows" :key="item.key || `row-${index}`" :class="rowClass(item)"
          @click="selectRow(item.key)">
        <td class="db-grid-no">{{ item.draft ? '新' : rowNumber(tab.page, tab.pageSize, index - (tab.drafts || []).length) }}</td>
        <td v-for="column in columns" :key="column.name" :class="cellClass(item, column)"
            :title="cellHint(cellValue(item, column))"
            @dblclick.stop="startEdit(item, column)">
          <input v-if="editing && editing.key === item.key && editing.column === column.name"
                 v-model="editing.value" v-focus class="cell-input" spellcheck="false"
                 :placeholder="editing.orig === 'null' ? '(NULL)' : (editing.orig === 'empty' ? `('')` : '')"
                 @keydown.enter.prevent="commit" @keydown.esc.prevent="cancel" @blur="commit"/>
          <template v-else>
            <span v-if="cellText(item, column).kind === 'null'" class="db-null">NULL</span>
            <span v-else-if="cellText(item, column).text === ''" class="db-empty">''</span>
            <span v-else :class="{'db-blob': cellText(item, column).kind === 'blob'}">{{ cellText(item, column).text }}</span>
          </template>
        </td>
        <td v-if="editable" class="db-grid-act">
          <button class="row-del" title="删除此行" @click.stop="removeRow(item)">×</button>
        </td>
      </tr>
      </tbody>
    </table>
    <div v-if="!displayRows.length" class="db-grid-empty">
      {{ tab.loading ? '正在读取…' : '当前条件下没有数据' }}
    </div>
  </div>
</template>
