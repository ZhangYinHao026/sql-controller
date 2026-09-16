<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { highlightSql } from '../utils/sqlHighlight'
import { formatSql } from '../utils/sqlFormat'
import { DB_REGISTRY, CONVERT_TARGETS, typeLabel } from '../utils/dbRegistry'
import ConvertResultPanel from './ConvertResultPanel.vue'

const props = defineProps({
    show: Boolean,
    // 全部数据库连接（App 侧传入）：用于「一键转换并执行」预检——按钮上显示将执行到哪些库、多少个
    connections: { type: Array, default: () => [] }
})
const emit = defineEmits(['close', 'use-result', 'execute-all'])

/* ---------------- 方言元数据：全部来自 dbRegistry，模板零硬编码 ---------------- */
const SRC = DB_REGISTRY.mysql || { label: 'MySQL' }   // 源方言（输入侧）
// 每个目标方言一个 UI 结构：key + 展示名 + 品牌色（新增目标只改 CONVERT_TARGETS / DB_REGISTRY）
const TARGET_META = Object.fromEntries(CONVERT_TARGETS.map(key => [
    key,
    { key, name: DB_REGISTRY[key]?.label || key, hint: DB_REGISTRY[key]?.hint || '',
      dot: DB_REGISTRY[key]?.dot || '#6BCEB2', bg: DB_REGISTRY[key]?.bg || '#E3F6EF', ink: DB_REGISTRY[key]?.ink || '#1F7A5F' }
]))

const inputSql = ref('')
const loading = ref(false)
const error = ref('')
const inputHighlight = computed(() => highlightSql(inputSql.value))
const inputArea = ref(null)
const MIN_INPUT = 160

// 勾选的目标方言（chips 驱动；结果面板 v-for 渲染，不写死数量）
const checked = reactive({})
CONVERT_TARGETS.forEach(key => { checked[key] = true })
const checkedKeys = computed(() => CONVERT_TARGETS.filter(key => checked[key]))

// 每方言结果槽：text（可编辑）/ warnings / status: idle|loading|done|error / error / ms
const results = reactive({})
CONVERT_TARGETS.forEach(key => {
    results[key] = { text: '', warnings: [], status: 'idle', error: '', ms: 0 }
})

// 视图模式：auto（≤3 并排 / >3 页签）| grid | tab
const viewMode = ref('auto')
const activeKey = ref(CONVERT_TARGETS[0] || '')
const effectiveView = computed(() =>
    viewMode.value === 'auto' ? (checkedKeys.value.length > 3 ? 'tab' : 'grid') : viewMode.value)
watch(checkedKeys, keys => {
    if (!keys.includes(activeKey.value)) activeKey.value = keys[0] || ''
})

function toggle(key) { checked[key] = !checked[key] }
function toggleAll() {
    const allOn = checkedKeys.value.length === CONVERT_TARGETS.length
    CONVERT_TARGETS.forEach(key => { checked[key] = !allOn })
}
function setMode(m) { viewMode.value = m }

/* ---------------- 示例快捷填入 ---------------- */
const EXAMPLES = {
    create: "CREATE TABLE t_user (\n  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',\n  name VARCHAR(64) NOT NULL COMMENT '姓名',\n  age INT COMMENT '年龄',\n  PRIMARY KEY (id)\n) COMMENT = '用户表';",
    alter:  "ALTER TABLE t_user\n  ADD COLUMN phone VARCHAR(20) COMMENT '手机号',\n  ADD COLUMN email VARCHAR(128) COMMENT '邮箱';",
    select: "SELECT u.id, u.name, COUNT(o.id) AS order_cnt\nFROM t_user u\nLEFT JOIN t_order o ON o.user_id = u.id\nWHERE u.age > 18\nGROUP BY u.id, u.name;"
}
const EXAMPLE_LABELS = { create: '建表', alter: '加字段', select: '查询' }
function loadExample(k) {
    inputSql.value = EXAMPLES[k]
    error.value = ''
    nextTick(() => autoSize(inputArea.value))
}

/* ---------------- 转换：按勾选目标并行请求，逐槽独立成败 ---------------- */
async function convert() {
    const sql = inputSql.value.trim()
    if (!sql || loading.value) return
    const keys = checkedKeys.value
    if (!keys.length) { error.value = '请至少勾选一个目标方言'; return }
    loading.value = true
    error.value = ''
    keys.forEach(key => {
        const r = results[key]
        r.text = ''; r.warnings = []; r.status = 'loading'; r.error = ''; r.ms = 0
    })
    try {
        await Promise.all(keys.map(async key => {
            const t0 = performance.now()
            try {
                const res = await api.convertSql(sql, key)
                if (res && res.success === false) {
                    results[key].status = 'error'
                    results[key].error = res.error || '转换失败'
                    return
                }
                // 紧凑模式：同一源语句拆出的多条目标语句之间不留空行；CREATE TABLE 列清单自动逐行展开
                results[key].text = formatSql(joinStatements(res), { gapStatements: false })
                results[key].warnings = res?.warnings || []
                results[key].status = 'done'
            } catch (e) {
                results[key].status = 'error'
                results[key].error = e.message || '转换失败，请重试'
            } finally {
                results[key].ms = Math.round(performance.now() - t0)
            }
        }))
    } finally {
        loading.value = false
    }
}

function joinStatements(result) {
    // 去掉每句自带的尾分号（后端可能带/不带），末尾统一补一个，避免出现 ;; 或末句无分号
    const list = (result?.statements || []).map(s => (s || '').trim().replace(/;+$/, '')).filter(Boolean)
    if (!list.length) return ''
    // 同一源语句（MySQL）可能被拆成多条目标语句（如 ALTER ADD COLUMN → ALTER + COMMENT ON），
    // 将紧随其后的 CREATE INDEX / COMMENT ON 并入同一逻辑块：块内分号换行紧凑相邻，块间空行分隔
    const groups = []
    for (const s of list) {
        if (/^(CREATE INDEX|COMMENT ON)\b/i.test(s) && groups.length) {
            groups[groups.length - 1].push(s)
        } else {
            groups.push([s])
        }
    }
    return groups.map(g => g.join(';\n')).join(';\n\n') + ';'
}

async function copy(text) {
    if (!text) return
    try {
        await navigator.clipboard.writeText(text)
    } catch {
        const ta = document.createElement('textarea')
        ta.value = text
        ta.style.position = 'fixed'
        ta.style.opacity = '0'
        document.body.appendChild(ta)
        ta.select()
        document.execCommand('copy')
        ta.remove()
    }
}

// 一键把转换结果送回主链路执行：App.vue 负责回填编辑器 + 切库型 + 预选连接
function useResult(key) {
    const r = results[key]
    if (!r?.text) return
    emit('use-result', { text: r.text, dbType: key, warnings: r.warnings || [] })
}

/* ---------------- 一键转换并执行：把原始 SQL 交给主链路做「全类型分组执行」 ---------------- */
// 按 dbType 归类现有连接（已知类型按注册表顺序、未注册类型排后），按钮上直接显示将执行到哪些库多少个
const execGroups = computed(() => {
    const counts = new Map()
    for (const c of props.connections || []) {
        const t = c?.dbType || 'unknown'
        counts.set(t, (counts.get(t) || 0) + 1)
    }
    const known = Object.keys(DB_REGISTRY).filter(t => counts.has(t))
    const unknown = [...counts.keys()].filter(t => !DB_REGISTRY[t])
    return [...known, ...unknown].map(t => ({ type: t, name: typeLabel(t), count: counts.get(t) }))
})
const execSummary = computed(() => execGroups.value.map(g => `${g.name} ${g.count}`).join(' · '))
const hasConnections = computed(() => (props.connections || []).length > 0)
const canExecAll = computed(() => hasConnections.value && !!inputSql.value.trim() && !loading.value)
const execTitle = computed(() => hasConnections.value
    ? `转成各目标方言后，对全部连接按类型执行（${execSummary.value}）`
    : '暂无可执行的数据库连接，请先到「设置」中添加')

// 弹窗内不做转换：确定「全部类型」需要 App 侧的 connections，且执行覆盖所有类型、忽略上方方言勾选。
// 只做输入校验→把原始 SQL 抛给 App.vue；由主链路负责 分组转换 → 二次确认 → 串行执行 → 关弹窗 → 结果回主界面。
function convertAndExecute() {
    const sql = inputSql.value.trim()
    if (!sql || !hasConnections.value) return
    error.value = ''
    emit('execute-all', { sql })
}

/* ---------------- 输入框自适应 / 滚动同步 ---------------- */
function autoSize(el) {
    if (!el) return
    el.style.height = 'auto'
    el.style.height = Math.max(el.scrollHeight, MIN_INPUT) + 'px'
}
function syncScroll(e) {
    const box = e.currentTarget.closest('.convert-code')
    const pre = box?.querySelector('.convert-highlight')
    if (pre) {
        pre.scrollTop = e.currentTarget.scrollTop
        pre.scrollLeft = e.currentTarget.scrollLeft
    }
}

watch(() => props.show, async v => {
    if (v) {
        await nextTick()
        if (inputArea.value) autoSize(inputArea.value)
    }
})
</script>

<template>
  <div v-if="show" class="modal-mask" @click.self="$emit('close')">
    <section class="modal convert-modal">
      <header class="modal-header">
        <h2>SQL 转换</h2>
        <button class="icon-btn" @click="$emit('close')">×</button>
      </header>

      <!-- 源输入 -->
      <div class="convert-box">
        <div class="src-meta">
          <span class="src-badge" :style="{ '--dot': SRC.dot, '--bg': SRC.bg, '--ink': SRC.ink }">
            <i></i>源 · {{ SRC.label }}
          </span>
          <div class="ex-chips">
            <button v-for="(lab, k) in EXAMPLE_LABELS" :key="k" class="ex-chip"
                    type="button" @click="loadExample(k)">示例：{{ lab }}</button>
          </div>
        </div>
        <div class="convert-code">
          <pre class="convert-highlight" aria-hidden="true" v-html="inputHighlight"></pre>
          <textarea ref="inputArea" v-model="inputSql" class="convert-input convert-input-tall" spellcheck="false"
                    placeholder="在此粘贴 MySQL DDL / DML，点击「一键转换」或「一键转换并执行」…"
                    @input="autoSize(inputArea)" @scroll="syncScroll"></textarea>
        </div>
        <div class="convert-actions">
          <span v-if="error" class="convert-error">⚠ {{ error }}</span>
          <span v-else-if="hasConnections" class="exec-hint">将执行到 {{ execSummary }}</span>
          <span v-else class="exec-hint">暂无数据库连接，请先到「设置」中添加</span>
          <button class="btn primary" :disabled="loading || !inputSql.trim()" @click="convert">
            {{ loading ? '转换中…' : '一键转换' }}
          </button>
          <button class="btn primary exec-btn" :disabled="!canExecAll" :title="execTitle" @click="convertAndExecute">
            ▶ 一键转换并执行
          </button>
        </div>
      </div>

      <!-- 目标方言 chips：注册表驱动；勾选决定渲染哪些结果面板 -->
      <div class="target-row">
        <div class="target-label">
          <span class="target-title">目标方言</span>
          <span class="target-count">已勾 {{ checkedKeys.length }}/{{ CONVERT_TARGETS.length }}</span>
          <button class="link-btn" type="button" @click="toggleAll">全选 / 清空</button>
        </div>
        <div class="chips">
          <button v-for="m in TARGET_META" :key="m.key" type="button" class="chip"
                  :class="{ on: checked[m.key] }" @click="toggle(m.key)">
            <i></i>{{ m.name }}<em>{{ m.hint }}</em>
          </button>
        </div>
      </div>

      <!-- 结果区：≤3 并排 grid，>3 自动页签（可手动覆盖） -->
      <div class="result-row">
        <div class="target-label">
          <span class="target-title">转换结果</span>
          <span class="target-count">
            <template v-if="!checkedKeys.length">未勾选目标方言</template>
            <template v-else>{{ effectiveView === 'grid' ? '并排视图 · 随勾选增减' : '页签视图 · 一次看一个' }}</template>
          </span>
        </div>
        <div class="seg">
          <button v-for="opt in [['auto','自动'],['grid','并排'],['tab','页签']]" :key="opt[0]" type="button"
                  :class="{ on: viewMode === opt[0] }" @click="setMode(opt[0])">{{ opt[1] }}</button>
        </div>
      </div>

      <div class="result-zone">
        <div v-if="!checkedKeys.length" class="zone-empty">
          勾选上方目标方言后，这里会按勾选动态生成结果面板（v-for 渲染，不写死数量）
        </div>

        <!-- 并排 grid -->
        <div v-else-if="effectiveView === 'grid'" class="panel-grid">
          <ConvertResultPanel v-for="key in checkedKeys" :key="key"
                              :db="TARGET_META[key]"
                              v-model="results[key].text"
                              :warnings="results[key].warnings"
                              :status="results[key].status"
                              :error="results[key].error"
                              :ms="results[key].ms"
                              @copy="copy" @use="useResult" />
        </div>

        <!-- 页签 single -->
        <template v-else>
          <div class="tabbar">
            <button v-for="key in checkedKeys" :key="key" type="button"
                    class="tab" :class="{ on: activeKey === key }" @click="activeKey = key">
              <i :style="{ background: TARGET_META[key].dot }"></i>{{ TARGET_META[key].name }}
            </button>
          </div>
          <ConvertResultPanel v-if="results[activeKey]"
                              :db="TARGET_META[activeKey]"
                              v-model="results[activeKey].text"
                              :warnings="results[activeKey].warnings"
                              :status="results[activeKey].status"
                              :error="results[activeKey].error"
                              :ms="results[activeKey].ms"
                              @copy="copy" @use="useResult" />
        </template>
      </div>

      <p class="zone-tip">
        新增目标数据库 = dbRegistry 追加一行 + 后端注册一个方言实现，弹窗自动长出 chip 与结果面板，模板零改动。
      </p>
    </section>
  </div>
</template>

<style scoped>
.convert-modal { max-height: 94vh; padding: 20px 24px 16px; overflow: auto; }

.convert-box { margin-bottom: 8px; }

/* 源输入行 */
.src-meta { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; flex-wrap: wrap; }
.src-badge {
    display: inline-flex; align-items: center; gap: 6px;
    font-size: 12px; font-weight: 800;
    color: var(--ink, var(--mint-700));
    background: var(--bg, var(--mint-100));
    padding: 3px 12px; border-radius: var(--r-pill);
}
.src-badge i { width: 7px; height: 7px; border-radius: 50%; background: var(--dot, var(--mint-400)); }
.ex-chips { margin-left: auto; display: flex; gap: 6px; flex-wrap: wrap; }
.ex-chip {
    border: 1px solid var(--line); background: #fff;
    font-size: 11.5px; font-weight: 600; color: var(--ink-700);
    padding: 3px 11px; border-radius: var(--r-pill); cursor: pointer;
    font-family: inherit; transition: all var(--t-fast) var(--ease);
}
.ex-chip:hover { border-color: var(--mint-300); color: var(--mint-700); transform: translateY(-1px); }

/* 双层代码框 */
.convert-code { position: relative; border-radius: var(--r-xs); }
.convert-highlight,
.convert-input {
    font-family: var(--font-mono);
    font-size: 13px;
    line-height: 21px;
    padding: 12px;
    border: 1px solid var(--line);
    border-radius: var(--r-xs);
    white-space: pre-wrap;
    word-break: break-word;
    margin: 0;
    box-sizing: border-box;
    letter-spacing: normal;
    word-spacing: normal;
    tab-size: 4;
}
.convert-highlight {
    position: absolute;
    inset: 0;
    border: 0;
    background: linear-gradient(180deg, #FFFDF7, #FFF8E8);
    color: var(--ink-900);
    overflow: hidden;
    pointer-events: none;
    z-index: 0;
}
.convert-input {
    background: transparent;
    color: transparent;
    caret-color: var(--ink-900);
    width: 100%;
    position: relative;
    z-index: 1;
    min-height: 80px;
    max-height: 320px;
    overflow: auto;
    resize: none;
}
.convert-input-tall { min-height: 160px; max-height: 480px; }
.convert-input::placeholder { color: var(--ink-300); }
.convert-input:focus { outline: none; border-color: var(--mint-300); box-shadow: 0 0 0 3px rgba(107, 206, 178, .18); }
.convert-input::selection { background: rgba(107, 206, 178, .35); color: transparent; }
.convert-highlight :deep(.sql-string) { color: #4FA377; }
.convert-highlight :deep(.sql-comment) { color: var(--ink-300); font-style: italic; }
.convert-highlight :deep(.sql-number) { color: #B07AC9; }

.convert-actions { display: flex; align-items: center; justify-content: flex-end; gap: 12px; margin-top: 10px; }
.convert-error { color: #B95A5A; margin-right: auto; font-size: 12.5px; }
.exec-hint { color: var(--ink-500); margin-right: auto; font-size: 12px; }

/* 「一键转换并执行」：与「一键转换」并列，用更深一档的主色区分（会写全部库，需一眼可辨）。
   全局 `.btn.primary` 与本类特异性同为 (0,2,0)，故用 `button.btn.exec-btn` 提高一级确保覆盖。 */
button.btn.exec-btn { background: linear-gradient(135deg, var(--mint-500), var(--mint-700)); }
button.btn.exec-btn:not(:disabled):hover { box-shadow: 0 10px 24px rgba(31, 122, 95, .42); }

/* chips 区 */
.target-row { display: flex; align-items: center; gap: 16px; margin-top: 16px; flex-wrap: wrap; }
.target-label { display: flex; align-items: center; gap: 8px; min-width: 0; }
.target-title { font-size: 14px; font-weight: 800; color: var(--ink-900); white-space: nowrap; }
.target-count { font-size: 11px; color: var(--ink-500); white-space: nowrap; }
.link-btn {
    border: 0; background: none; color: var(--mint-600);
    font-size: 11.5px; font-weight: 700; cursor: pointer; font-family: inherit; padding: 0;
}
.link-btn:hover { color: var(--mint-700); text-decoration: underline; }
.chips { display: flex; gap: 8px; flex-wrap: wrap; flex: 1; min-width: 0; }
.chip {
    display: inline-flex; align-items: center; gap: 6px;
    border: 1.5px solid var(--line); background: #fff;
    font-size: 13px; font-weight: 800; color: var(--ink-700);
    padding: 5px 13px 5px 9px; border-radius: var(--r-pill); cursor: pointer;
    font-family: inherit; transition: all .16s var(--ease);
}
.chip i { width: 8px; height: 8px; border-radius: 50%; background: var(--dot, var(--mint-400)); flex: 0 0 8px; }
.chip em { font-style: normal; font-weight: 500; font-size: 11px; color: var(--ink-300); margin-left: -2px; }
.chip:hover { transform: translateY(-1px); border-color: #c9dcd3; }
.chip.on { border-color: var(--ink, var(--mint-600)); background: var(--bg, var(--mint-100)); color: var(--ink, var(--mint-700)); }
.chip.on em { color: inherit; opacity: .75; }

/* 结果区标题 + seg */
.result-row { display: flex; align-items: center; justify-content: space-between; margin-top: 16px; flex-wrap: wrap; gap: 10px; }
.seg { display: inline-flex; background: #F1ECE3; border-radius: var(--r-pill); padding: 3px; }
.seg button {
    border: 0; background: transparent; font-size: 12px; font-weight: 700;
    color: var(--ink-500); padding: 3px 12px; border-radius: var(--r-pill);
    cursor: pointer; font-family: inherit;
}
.seg button.on { background: #fff; color: var(--mint-700); box-shadow: 0 1px 4px rgba(0, 0, 0, .08); }

.result-zone { margin-top: 12px; }
.zone-empty {
    border: 1.5px dashed var(--mint-200); background: var(--mint-50);
    border-radius: var(--r-md); padding: 26px 18px; text-align: center;
    color: var(--ink-500); font-size: 13px;
}
.panel-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 14px; align-items: stretch; }

/* 页签 */
.tabbar { display: flex; gap: 8px; flex-wrap: wrap; border-bottom: 2px solid var(--line); padding-bottom: 10px; margin-bottom: 12px; }
.tab {
    display: inline-flex; align-items: center; gap: 6px;
    border: 1px solid var(--line); background: #fff;
    font-size: 13px; font-weight: 800; color: var(--ink-500);
    padding: 5px 14px 5px 9px; border-radius: var(--r-pill); cursor: pointer;
    font-family: inherit; transition: all .16s var(--ease);
}
.tab i { width: 8px; height: 8px; border-radius: 50%; }
.tab:hover { color: var(--ink-700); }
.tab.on { background: var(--mint-100); color: var(--mint-700); border-color: transparent; }

.zone-tip { font-size: 11px; color: var(--ink-300); margin: 14px 0 2px; line-height: 1.7; }
</style>
