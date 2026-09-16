<script setup>
import { computed, nextTick, ref } from 'vue'
import CommandTreeNode from './CommandTreeNode.vue'

const props = defineProps({ nodes: { type: Array, default: () => [] }, loading: Boolean })
const emit = defineEmits(['create', 'update', 'remove', 'send', 'reload', 'error', 'convert'])
const collapsed = ref(false)
const activeMenu = ref('script')
const width = ref(300)
const selectedId = ref(null)
const nameInput = ref(null)
const sqlInput = ref(null)
const context = ref(null)
const draft = ref(null)
const paramValues = ref({})
const rootNodes = computed(() => props.nodes.filter(node => node.parentId == null))
const folders = computed(() => props.nodes.filter(node => node.nodeType === 'folder'))
const contextParent = ref(null)

function scriptParameters(text = '') {
  const seen = new Set()
  const parameters = []
  const validName = name => /^[\p{L}\p{N}_]+$/u.test(name)
  const add = (mode, name) => {
    const key = `${mode}:${name}`
    if (validName(name) && !seen.has(key)) {
      seen.add(key)
      parameters.push({ mode, name })
    }
  }
  let index = 0
  while (index < text.length) {
    const current = text[index]
    if (['\'', '"', '`'].includes(current)) {
      const quote = current
      index += 1
      while (index < text.length) {
        if (text[index] === '\\') index += 2
        else if (text[index] === quote) {
          index += text[index + 1] === quote ? 2 : 1
          break
        } else index += 1
      }
    } else if (text.startsWith('--', index)) {
      index = text.indexOf('\n', index + 2)
      if (index < 0) break
    } else if (text.startsWith('/*', index)) {
      const end = text.indexOf('*/', index + 2)
      index = end < 0 ? text.length : end + 2
    } else if (current === '#' && !text.startsWith('#{', index)) {
      index = text.indexOf('\n', index + 1)
      if (index < 0) break
    } else {
      const match = text.slice(index).match(/^(?:([#$])\{([^{}\s]+)\}|\{([^#$}][^{}\s]*)\})/)
      if (match) {
        add(match[1] || '#', match[2] || match[3])
        index += match[0].length
      } else index += 1
    }
  }
  return parameters
}

const paramNames = computed(() => {
  const text = draft.value?.nodeType === 'file' ? draft.value.sqlText || '' : ''
  return scriptParameters(text)
})

function childrenOf(id) {
  return props.nodes.filter(node => String(node.parentId) === String(id))
}

function toggleMenu(menu) {
  if (activeMenu.value === menu) {
    collapsed.value = !collapsed.value
    if (!collapsed.value && menu === 'script') emit('reload')
    return
  }
  activeMenu.value = menu
  collapsed.value = false
  if (menu === 'script') emit('reload')
}

function toggleConvert() {
  emit('convert')
}

function showContext(node, event) {
  if (node && node.node) {
    event = node.event
    node = node.node
  }
  contextParent.value = node?.nodeType === 'folder' ? node.id : (node?.parentId ?? null)
  context.value = { node, x: event.clientX, y: event.clientY }
}

function selectNode(node) {
  if (node.nodeType === 'folder') return toggleFolder(node)
  selectedId.value = node.id
  draft.value = { ...node }
  paramValues.value = Object.fromEntries(paramNames.value.map(param => [param.name, '']))
  context.value = null
}

function toggleFolder(node) {
  emit('update', node.id, { ...node, isOpen: !node.isOpen }, { silent: true })
}

function openCreate(parentId = contextParent.value) {
  context.value = null
  draft.value = { id: null, nodeType: 'file', name: '', sqlText: '', parentId, sortOrder: 0, isOpen: true }
  paramValues.value = {}
  nextTick(() => nameInput.value?.focus())
}

function openFolder(parentId = contextParent.value) {
  context.value = null
  draft.value = { id: null, nodeType: 'folder', name: '', sqlText: null, parentId, sortOrder: 0, isOpen: true }
  nextTick(() => nameInput.value?.focus())
}

function createInContext(type) {
  const parentId = contextParent.value
  if (type === 'folder') openFolder(parentId)
  else openCreate(parentId)
}

function openRename(node) {
  context.value = null
  selectedId.value = node.id
  draft.value = { ...node }
  paramValues.value = Object.fromEntries(paramNames.value.map(param => [param.name, '']))
}

function executeDraft() {
  if (!draft.value?.sqlText?.trim()) return emit('error', '请输入 SQL 命令')
  for (const param of paramNames.value) {
    if (!String(paramValues.value[param.name] ?? '').trim()) return emit('error', `请填写运行参数：${param.name}`)
  }
  // 参数替换：#{name}/{name} 作为 SQL 字符串字面量（自动加引号、转义单引号），
  //           ${name} 原样替换（不加引号，用于表名/列名等标识符）
  let sqlText = draft.value.sqlText
  for (const param of paramNames.value) {
    const raw = paramValues.value[param.name]
    const value = param.mode === '$'
      ? String(raw)
      : `'${String(raw).replace(/'/g, "''")}'`
    sqlText = sqlText.replace(new RegExp(`[#$]?\\{${param.name}\\}`, 'g'), value)
  }
  emit('send', { node: { ...draft.value, sqlText }, params: { ...paramValues.value } })
}

function sendNode(node) {
  if (scriptParameters(node.sqlText).length) return selectNode(node)
  emit('send', { node, params: {} })
}

function saveDraft() {
  if (!draft.value?.name?.trim() || (draft.value.nodeType === 'file' && !draft.value.sqlText?.trim())) {
    emit('error', '请填写名称和 SQL 命令')
    return
  }
  const node = { ...draft.value, parentId: draft.value.parentId ?? null, name: draft.value.name.trim() }
  if (node.id) emit('update', node.id, node)
  else emit('create', node)
  draft.value = null
}

function removeNode(node) {
  context.value = null
  if (String(node.id) === '1') {
    emit('error', '默认分类不能删除')
    return
  }
  if (!confirm(`确定删除“${node.name}”吗？目录下的命令也会被删除。`)) return
  emit('remove', node.id)
  if (String(selectedId.value) === String(node.id)) {
    selectedId.value = null
    draft.value = null
  }
}

function insertParam(token) {
  const input = sqlInput.value
  if (!input || !draft.value) return
  const position = input.selectionStart
  const value = `${token}{参数${token === '#' ? '1' : '2'}}`
  draft.value.sqlText = draft.value.sqlText.slice(0, position) + value + draft.value.sqlText.slice(input.selectionEnd)
  nextTick(() => {
    input.focus()
    input.setSelectionRange(position + value.length, position + value.length)
  })
}

function startResize(event) {
  const startX = event.clientX
  const startWidth = width.value
  const move = current => { width.value = Math.min(500, Math.max(280, startWidth + current.clientX - startX)) }
  const stop = () => {
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', stop)
  }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', stop)
}
</script>

<template>
  <aside class="command-shell" :class="{ collapsed }" :style="{ width: `${width}px` }">
    <div class="command-icon-strip">
      <button class="command-icon" :class="{ active: activeMenu === 'script' }" title="SQL 脚本" @click="toggleMenu('script')">⌘</button>
      <button class="command-icon" title="SQL 转换" @click="toggleConvert">⇄</button>
    </div>
    <div v-if="!collapsed" class="command-panel">
      <header class="command-panel-head">
        <strong>{{ activeMenu === 'script' ? 'SQL 脚本' : 'SQL 转换' }}</strong>
        <button class="plain-icon" title="收起" @click="collapsed = true">‹</button>
      </header>
      <div v-if="activeMenu === 'script'" class="command-tree" @click.self="selectedId = null" @contextmenu.self.prevent="showContext(null, $event)">
        <div v-if="loading" class="command-empty">正在加载命令…</div>
        <template v-else-if="rootNodes.length">
          <CommandTreeNode v-for="node in rootNodes" :key="node.id" :node="node" :selected-id="selectedId"
                           :children-of="childrenOf" @select="selectNode" @context="showContext" @send="sendNode" />
        </template>
        <div v-else class="command-empty">右键此处可新建文件夹或命令</div>
      </div>
      <div v-else class="command-empty">SQL 转换功能准备中</div>
      <section v-if="draft && activeMenu === 'script'" class="command-editor">
        <div class="command-editor-head">
          <b>{{ draft.id ? '参数 / 编辑' : draft.nodeType === 'folder' ? '新建文件夹' : '添加命令' }}</b>
          <button class="link-btn" @click="draft = null">取消</button>
        </div>
        <label>名称<input v-model.trim="draft.name" ref="nameInput" /></label>
        <label>保存到
          <select v-model.number="draft.parentId">
            <option :value="null">根目录</option>
            <option v-for="folder in folders.filter(item => item.id !== draft.id)" :key="folder.id" :value="folder.id">{{ folder.name }}</option>
          </select>
        </label>
        <template v-if="draft.nodeType === 'file'">
          <label>SQL 命令<textarea v-model="draft.sqlText" ref="sqlInput" spellcheck="false" /></label>
          <div class="param-actions"><span>插入参数</span><button title="#{参数}：预编译绑定，适合字符串、数字、日期" @click="insertParam('#')"># 参数</button><button title="${参数}：直接替换 SQL 文本，只适合受控的列名、排序等片段" @click="insertParam('$')">$ 参数</button></div>
          <div v-if="paramNames.length" class="run-params">
            <label v-for="param in paramNames" :key="`${param.mode}:${param.name}`"><span>{{ param.mode }} {{ param.name }}</span><input v-model="paramValues[param.name]" :placeholder="param.mode === '#' ? `请输入 ${param.name}` : `请输入 ${param.name}`" /></label>
          </div>
        </template>
        <div class="command-editor-actions">
          <button class="btn primary small" @click="saveDraft">保存</button>
          <button v-if="draft.nodeType === 'file'" class="btn small" @click="executeDraft">▶ 执行</button>
        </div>
      </section>
    </div>
    <div class="command-resizer" @mousedown.prevent="startResize" />
    <div v-if="context" class="command-context" :style="{ left: `${context.x}px`, top: `${context.y}px` }" @mouseleave="context = null">
      <button @click="createInContext('folder')"><img src="/icon/new-folder.png" alt="">新建文件夹...</button>
      <button v-if="context.node" @click="openRename(context.node)"><img src="/icon/rename.png" alt="">重命名</button>
      <button v-if="context.node" @click="removeNode(context.node)"><img src="/icon/delete.png" alt="">删除</button>
      <button @click="createInContext('file')"><img src="/icon/add-command.png" alt="">添加命令...</button>
    </div>
  </aside>
</template>
