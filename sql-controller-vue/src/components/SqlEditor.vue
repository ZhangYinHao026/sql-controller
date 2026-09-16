<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { EditorView, keymap, lineNumbers, placeholder } from '@codemirror/view'
import { EditorState, Compartment } from '@codemirror/state'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { sql, MySQL } from '@codemirror/lang-sql'
import { autocompletion, completionKeymap, acceptCompletion } from '@codemirror/autocomplete'
import { defaultKeymap, history, historyKeymap, indentWithTab, indentLess } from '@codemirror/commands'
import { tags } from '@lezer/highlight'
import { formatSql } from '../utils/sqlFormat'
import { paramDecoration } from '../utils/paramDecoration'
import { loadAutocompleteSchema, toCM6Schema } from '../utils/autocompleteSchema'

const props = defineProps({
  modelValue: { type: String, required: true },
  transactional: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'execute', 'update:transactional'])

const container = ref(null)
const cmFailed = ref(false)
let view = null
let updating = false
/* 语言扩展放 Compartment：初始用纯 MySQL 方言，schema 注入后用带 schema 的版本替换（避免重复注册 SQL 语言） */
const schemaCompartment = new Compartment()

/* ===== 奶油暖光主题（恢复历史样式） ===== */
const editorTheme = EditorView.theme({
  '&': {
    height: '100%',
    fontSize: '13px',
    /* 编辑区：自上而下奶油渐变（与原 #FFFDF7 → #FFF8E8 一致） */
    background: 'linear-gradient(180deg, #FFFDF7 0%, #FFF8E8 100%)',
    color: 'var(--ink-700)'
  },
  '.cm-scroller': {
    fontFamily: 'var(--font-mono)',
    lineHeight: '21px',
    overflow: 'auto',
    /* 滚动区域沿用渐变，避免滚动时露出纯白底 */
    background: 'linear-gradient(180deg, #FFFDF7 0%, #FFF8E8 100%)'
  },
  '.cm-content': {
    minHeight: '100%',
    padding: '12px 0',
    caretColor: 'var(--ink-900)'
  },
  '.cm-line': { padding: '0 14px' },
  '&.cm-focused': { outline: 'none' },
  '.cm-gutters': {
    /* 行号列：奶油色 #fff8e5 */
    background: '#fff8e5',
    color: 'var(--ink-300)',
    /* 去掉行号与编辑区之间的竖线 */
    borderRight: 'none',
    fontFamily: 'var(--font-mono)',
    fontSize: '13px'
  },
  /* 行号宽度：想调宽/调窄改 minWidth 与 paddingLeft/Right */
  '.cm-lineNumbers .cm-gutterElement': {
    minWidth: '3.6em',
    paddingLeft: '14px',
    paddingRight: '12px'
  },
  '.cm-activeLine': { background: 'rgba(255, 233, 184, .25)' },
  '.cm-activeLineGutter': { background: 'rgba(255, 233, 184, .55)', color: 'var(--ink-500)' },
  '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': {
    background: 'rgba(209, 122, 63, .18) !important'
  },
  /* 联想下拉：奶油暖光风格（字体/圆角/配色均可在此改） */
  '.cm-tooltip': {
    backgroundColor: 'var(--surface)',
    border: '1px solid var(--cream-200)',
    borderRadius: '12px',
    boxShadow: '0 12px 32px rgba(95, 75, 60, .16)',
    fontFamily: 'var(--font-sans)',
    fontSize: '13px',
    overflow: 'hidden'
  },
  '.cm-tooltip.cm-tooltip-autocomplete': { padding: '5px' },
  '.cm-tooltip-autocomplete': {
    '& > ul': { fontFamily: 'var(--font-mono)', maxHeight: '280px' },
    '& ul li': {
      padding: '6px 10px',
      margin: '1px 0',
      color: 'var(--ink-900)',
      fontSize: '13px',
      borderRadius: '7px',
      display: 'flex',
      alignItems: 'center',
      gap: '6px',
      transition: 'background 120ms ease'
    },
    '& ul li:hover': { background: 'var(--cream-50)' },
    '& ul li[aria-selected]': {
      background: 'linear-gradient(135deg, var(--cream-100), #FFE9B8)',
      color: '#8A5A1E',
      boxShadow: 'inset 0 0 0 1px var(--cream-200)'
    }
  },
  '.cm-completionIcon': {
    fontSize: '12px',
    width: '18px',
    flex: '0 0 18px',
    textAlign: 'center',
    opacity: '.85'
  },
  '.cm-completionDetail': {
    color: 'var(--ink-300)',
    fontStyle: 'normal',
    fontSize: '11px',
    marginLeft: 'auto',
    paddingLeft: '10px'
  },
  '.cm-completionMatchedText': {
    color: '#D17A3F',
    fontWeight: 700
  },
  /* {参数} 占位符底纹（paramDecoration 注入的 .cm-param） */
  '.cm-param': {
    background: 'rgba(209, 122, 63, .16)',
    borderRadius: '3px',
    boxShadow: '0 0 0 1px rgba(209, 122, 63, .12)'
  },
  '.cm-cursor': { borderLeftColor: 'var(--ink-900)' }
})

/* 语法高亮颜色（对齐原 .sql-keyword #D17A3F / string #4FA377 / number #B07AC9 / comment 灰） */
const highlightStyle = HighlightStyle.define([
  { tag: tags.keyword, color: '#D17A3F', fontWeight: 600 },
  { tag: [tags.string, tags.special(tags.string)], color: '#4FA377' },
  { tag: [tags.number, tags.integer, tags.float], color: '#B07AC9' },
  { tag: [tags.comment, tags.lineComment, tags.blockComment], color: 'var(--ink-300)', fontStyle: 'italic' },
  { tag: [tags.function(tags.variableName), tags.function(tags.propertyName)], color: '#8E24AA' },
  { tag: tags.variableName, color: 'var(--ink-900)' },
  { tag: [tags.operator, tags.punctuation], color: 'var(--ink-700)' },
  { tag: [tags.bool, tags.null, tags.atom], color: '#B07AC9' },
  { tag: tags.typeName, color: '#1E88E5' }
])

/* ===== 对外操作 ===== */
function selectedText() {
  if (!view) return ''
  const sel = view.state.selection.main
  return sel.empty ? '' : view.state.doc.sliceString(sel.from, sel.to)
}
function execute() {
  emit('execute', selectedText())
}
function format() {
  const result = formatSql(props.modelValue || '')
  if (view) view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: result } })
  view?.focus()
}
function clear() {
  if (view) view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: '' } })
  view?.focus()
}

onMounted(async () => {
  const updateListener = EditorView.updateListener.of(u => {
    if (u.docChanged && !updating) emit('update:modelValue', u.state.doc.toString())
  })
  try {
    const state = EditorState.create({
      doc: props.modelValue || '',
      extensions: [
        lineNumbers(),
        history(),
        /* 语言扩展用 Compartment 包裹：初始纯 MySQL；schema 注入后 reconfigure 替换为带 schema 版本 */
        schemaCompartment.of(sql({ dialect: MySQL })),
        autocompletion({ activateOnTyping: true }),
        keymap.of([...defaultKeymap, ...completionKeymap, ...historyKeymap, {
          /* Tab：联想菜单打开且选中项时接受（与回车一致）；否则回落到缩进；Shift+Tab 反缩进 */
          key: 'Tab',
          run: c => acceptCompletion(c),
          shift: c => indentLess(c)
        }, indentWithTab, {
          /* Alt+X 快捷执行（等价点击「▶ 执行 SQL」） */
          key: 'Alt-x',
          run: () => { execute(); return true }
        }]),
        editorTheme,
        syntaxHighlighting(highlightStyle),
        paramDecoration,
        updateListener
      ]
    })
    view = new EditorView({ state, parent: container.value })
  } catch (e) {
    console.error('[SqlEditor] CodeMirror 初始化失败，回退为 textarea:', e)
    cmFailed.value = true
  }

  /* 懒加载联想 schema 并注入（enabled=false / 失败 → 纯关键字联想；注入失败不影响编辑）
     每次挂载都 force=true 拉最新 — 避免浏览器 localStorage 中旧 schema（被误认为还在 TTL 内）让联想长期落后于后端；
     同一会话内因 memoryCache 不会重复拉。 */
  try {
    const schema = await loadAutocompleteSchema(true)
    if (view && schema?.enabled && Array.isArray(schema.tables)) {
      view.dispatch({ effects: schemaCompartment.reconfigure(sql({ dialect: MySQL, schema: toCM6Schema(schema) })) })
    }
  } catch (e) {
    console.warn('[SqlEditor] 联想 schema 注入失败，保持纯关键字联想:', e)
  }
})

onBeforeUnmount(() => {
  view?.destroy()
  view = null
})

/* 外部 v-model 变化（如脚本「➤ 发送」追加、格式化）→ 同步到编辑器 */
watch(() => props.modelValue, val => {
  if (!view || cmFailed.value) return
  const cur = view.state.doc.toString()
  if (cur !== val) {
    updating = true
    view.dispatch({
      changes: { from: 0, to: cur.length, insert: val || '' },
      selection: { anchor: (val || '').length }
    })
    updating = false
  }
})
</script>

<template>
  <section class="card editor-card">
    <div class="toolbar">
      <button class="btn primary" title="快捷键 Alt+X" @click="execute">▶ 执行 SQL</button>
      <button class="btn" @click="format">格式化 SQL</button>
      <button class="btn danger" @click="clear">清空</button>
      <label class="tx-control" title="整段脚本单事务：全部成功统一提交，任一条失败整体回滚。仅对纯 DML（多条 INSERT/UPDATE/DELETE）生效；含 CREATE/ALTER 等 DDL 时数据库会隐式提交、无法回滚。单条语句开不开无区别。">
        <button type="button" class="switch" :class="{ on: transactional }" @click="emit('update:transactional', !transactional)"></button>
        <span>单事务</span>
        <span class="tx-tip">多条增删改：全成或全撤；建表/加字段无效</span>
      </label>
    </div>
    <div v-if="!cmFailed" ref="container" class="cm-editor-wrap"></div>
    <textarea v-else class="sql-input-fallback" :value="modelValue" spellcheck="false"
               @input="$emit('update:modelValue', $event.target.value)"></textarea>
  </section>
</template>

<style scoped>
.toolbar {
  flex-wrap: wrap;
  row-gap: 6px;
}
.tx-control {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  font-size: 12.5px;
  color: var(--ink-700);
  cursor: pointer;
  user-select: none;
}
.tx-control .switch { cursor: pointer; flex: none; }
.tx-tip { color: var(--muted); font-size: 12px; }
.cm-editor-wrap {
  height: 600px;
  overflow: hidden;
  border-top: 1px solid var(--cream-200);
  border-bottom: 1px solid var(--cream-200);
}
.cm-editor-wrap :deep(.cm-editor) { height: 100%; }
.cm-editor-wrap :deep(.cm-editor.cm-focused) { outline: none; }
.cm-editor-wrap :deep(.cm-scroller) { overflow: auto; }

/* CM6 初始化失败的兜底 textarea（保证可编辑 + 脚本发送可用） */
.sql-input-fallback {
  display: block;
  width: 100%;
  box-sizing: border-box;
  height: 600px;
  padding: 14px;
  border: 0;
  resize: none;
  background: var(--surface);
  font: 13px/21px var(--font-mono);
  color: var(--ink-900);
  outline: none;
}
</style>
