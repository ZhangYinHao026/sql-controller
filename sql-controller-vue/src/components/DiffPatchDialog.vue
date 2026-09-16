<script setup>
/**
 * 补丁脚本工作台（按目标库分桶）
 *
 * 每个目标库（各比对库 + 基准库）一份独立补丁桶，生成时只累积、不打断：
 *   左侧 = 目标库清单（待执行 N 行 / 执行中 / ✓ 已执行 / ✗ 失败可重试）；
 *   右侧 = 选中桶的脚本，可编辑 / 复制 / 清空 / 执行到目标库 / 执行全部待执行。
 *
 * 状态（text / edited / status / info）由 SchemaDiffDialog 持有并注入：
 *   文本编辑 emit('input') 由父级写回并打「已手工修改」标；
 *   执行 = emit('run')（当前桶）/ emit('run-all')（全部待执行桶，父级串行）。
 *   关闭 = × 按钮 / Esc（Esc 由父级统一处理：guard → 本弹窗 → 主面板）；遮罩点击不关闭。
 */
import { computed } from 'vue'

const props = defineProps({
  show: Boolean,
  items: { type: Array, default: () => [] },   // 有序桶视图 {key,name,roleName,dbType,lines,meta,status,info,canExec,execLabel,hasText,dirty}
  active: { type: Object, default: null },      // 当前选中桶视图（null = 未选中）
  busy: Boolean                                 // 任一桶执行中
})
const emit = defineEmits(['close', 'select', 'input', 'clear', 'copy', 'run', 'run-all'])

const pending = computed(() => props.items.filter(it => it.hasText && it.status !== 'running'))
const runAllLabel = computed(() => `执行全部（${pending.value.length} 库）`)
const emptyEditor = computed(() => !props.active || !props.active.hasText)
function onInput(e) { emit('input', e.target.value) }
const ST = {
  idle: { txt: '待执行', cls: 'st-idle' },
  running: { txt: '执行中…', cls: 'st-run' },
  done: { txt: '✓ 已执行', cls: 'st-done' },
  fail: { txt: '✗ 失败 · 可重试', cls: 'st-fail' }
}
</script>

<template>
  <div v-if="show" class="modal-mask patch-mask">
    <section class="modal patch-modal">
      <header class="modal-header">
        <h2>补丁脚本</h2>
        <span class="patch-sub">按目标库分桶累积 · 生成不打断 · 可逐库或统一执行</span>
        <button type="button" class="icon-btn" title="关闭" @click="emit('close')">×</button>
      </header>

      <div class="pd-body">
        <!-- 左侧：目标库清单 -->
        <aside class="pd-libs">
          <div class="pd-libs-title">目标库<template v-if="pending.length"> · {{ pending.length }} 库待执行</template></div>
          <div v-if="!items.length" class="pd-empty">
            尚未生成补丁。<br>在 差异总览 → ⊕ 自动补齐缺列 / 缺表建表，或单表核对 → 生成选中动作 时，<br>脚本会按目标库自动累积到这里。
          </div>
          <button v-for="it in items" :key="it.key" type="button" class="pd-lib"
                  :class="{ active: active && active.key === it.key }" @click="emit('select', it.key)">
            <span class="pd-lib-top">
              <span class="nm">{{ it.name }}</span>
              <span class="role" :class="it.roleName === '基准补丁' ? 'role-base' : 'role-cmp'">{{ it.roleName }}</span>
            </span>
            <span class="pd-lib-bot">
              <span class="st" :class="ST[it.status].cls">{{ it.status === 'idle' && !it.hasText ? '空' : ST[it.status].txt }}</span>
              <span class="lines">{{ it.lines }} 行</span>
            </span>
          </button>
          <div class="pd-legend">
            <span><i class="dot d-idle"></i>待执行</span>
            <span><i class="dot d-run"></i>执行中</span>
            <span><i class="dot d-done"></i>已执行</span>
            <span><i class="dot d-fail"></i>失败</span>
          </div>
        </aside>

        <!-- 右侧：当前桶内容 -->
        <div class="pd-main">
          <template v-if="active">
            <div class="pd-bar">
              <span class="patch-owner">{{ active.name }}（{{ active.dbType || '未知' }}）</span>
              <span class="st big" :class="ST[active.status].cls">{{ active.status === 'idle' && !active.hasText ? '空' : ST[active.status].txt }}</span>
              <span class="pd-info muted" v-if="active.info">{{ active.info }}</span>
              <span class="muted pd-meta">{{ active.meta }}</span>
            </div>
            <textarea :value="active.text" class="patch-out" spellcheck="false" :disabled="busy"
                      :placeholder="active.roleName === '基准补丁'
                        ? '基准补丁：来自单表核对中勾选「补基准库 ADD」，目标为基准库（把比对库多出的字段反向补齐）。\n可手工修改后 复制 / 执行到 目标库。'
                        : '比对补丁：来自 差异总览 → ⊕ 自动补齐缺列 / ⊕ 缺表建表，或 单表核对 → 生成选中动作，目标为该比对库。\n生成只累积不打断，可继续核对/生成其它库，最后统一执行。'"
                      @input="onInput"></textarea>
            <div class="pd-actions">
              <button type="button" class="btn danger-ghost" :disabled="emptyEditor || busy" title="清空当前目标库补丁内容" @click="emit('clear')">清空补丁</button>
              <button type="button" class="btn" :disabled="emptyEditor || busy" @click="emit('copy')">复制 SQL</button>
              <span class="muted pd-warn" v-if="active.dirty">⚠ 含 DROP / MODIFY：执行时需二次确认</span>
              <span class="muted pd-warn" v-else>纯新增语句：确认后直接执行</span>
              <button type="button" class="btn primary" :disabled="!active.canExec || busy"
                      @click="emit('run')">{{ active.status === 'fail' ? '↻ 重试执行到 ' + active.name : active.execLabel }}</button>
            </div>
          </template>
          <div v-else class="pd-empty big">在左侧选择一个目标库查看脚本</div>
        </div>
      </div>

      <footer class="pd-footer">
        <span class="muted">执行成功即清空该库脚本并保留 ✓ 状态；失败保留内容可重试；「执行全部」按库串行执行。</span>
        <button type="button" class="btn" :disabled="!pending.length || busy" :title="pending.length ? '按库串行执行全部待执行补丁（每库独立确认/转换）' : '没有待执行的补丁'"
                @click="emit('run-all')">{{ busy ? '执行中…' : runAllLabel }}</button>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.patch-mask { z-index: 45; }
.patch-modal { width: min(1180px, 100%); display: flex; flex-direction: column; gap: 10px; }
.patch-sub { font-size: 11px; color: var(--ink-300); margin-right: auto; }

.pd-body { display: flex; gap: 14px; min-height: 0; flex: 1 1 auto; }
/* 左侧清单 */
.pd-libs { width: 240px; flex: 0 0 240px; display: flex; flex-direction: column; gap: 6px; border-right: 1px solid var(--ink-100); padding-right: 12px; max-height: 62vh; overflow-y: auto; }
.pd-libs-title { font-size: 12px; font-weight: 800; color: var(--ink-500); }
.pd-lib {
  display: flex; flex-direction: column; gap: 4px; text-align: left;
  border: 1.5px solid var(--ink-100); border-radius: 10px; background: var(--surface);
  padding: 8px 10px; cursor: pointer; transition: all var(--t-fast) var(--ease);
}
.pd-lib:hover { border-color: var(--mint-300); }
.pd-lib.active { background: var(--mint-100); border-color: var(--mint-300); }
.pd-lib-top { display: flex; align-items: center; gap: 6px; }
.pd-lib-top .nm { font-size: 12.5px; font-weight: 800; color: var(--ink-700); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1 1 auto; }
.role { font-size: 10px; font-weight: 800; border-radius: var(--r-pill); padding: 0 8px; line-height: 16px; flex: 0 0 auto; }
.role-cmp { background: var(--cream-200); color: #8A6210; }
.role-base { background: var(--ink-100); color: var(--ink-500); }
.pd-lib-bot { display: flex; align-items: center; justify-content: space-between; }
.pd-lib-bot .lines { font-size: 10.5px; color: var(--ink-300); font-family: var(--font-mono); }
.st { font-size: 10.5px; font-weight: 800; border-radius: var(--r-pill); padding: 1px 8px; line-height: 16px; }
.st.big { font-size: 11.5px; padding: 2px 12px; }
.st-idle { background: #FFF3D6; color: #C77B00; }
.st-run { background: #E3F0FF; color: #2E6FB7; animation: pd-pulse 1.2s ease-in-out infinite; }
.st-done { background: #E4F4E8; color: #3E8E5A; }
.st-fail { background: #FBE7E7; color: #C94F4F; }
@keyframes pd-pulse { 0%, 100% { opacity: 1; } 50% { opacity: .55; } }
.pd-empty { font-size: 12px; color: var(--ink-300); line-height: 1.9; padding: 18px 4px; }
.pd-empty.big { text-align: center; padding: 60px 0; }
.pd-legend { display: flex; flex-wrap: wrap; gap: 8px; margin-top: auto; padding-top: 10px; font-size: 10.5px; color: var(--ink-400); }
.pd-legend span { display: inline-flex; align-items: center; gap: 4px; }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.d-idle { background: #C77B00; } .d-run { background: #2E6FB7; } .d-done { background: #3E8E5A; } .d-fail { background: #C94F4F; }

/* 右侧主区 */
.pd-main { flex: 1 1 auto; display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.pd-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.patch-owner {
  font-size: 12px; font-weight: 800; color: var(--mint-700);
  background: var(--mint-100); border-radius: var(--r-pill); padding: 2px 12px;
}
.pd-meta { margin-left: auto; font-size: 11px; }
.pd-info { font-size: 11.5px; font-family: var(--font-mono); max-width: 60%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.patch-out {
  width: 100%; box-sizing: border-box; min-height: 44vh; resize: vertical;
  padding: 12px 14px; border: 1px solid var(--ink-200); border-radius: 10px;
  background: linear-gradient(180deg, #FFFDF7, #FFF8E8);
  color: var(--ink-700); font: 12.5px/1.6 var(--font-mono); white-space: pre;
  outline: none;
}
.patch-out:focus { border-color: var(--mint-300); }
.patch-out:disabled { opacity: .75; cursor: not-allowed; }

.pd-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.pd-warn { font-size: 11px; margin-left: auto; }
.pd-actions .btn.primary { margin-left: 0; }

.pd-footer { display: flex; align-items: center; gap: 12px; justify-content: space-between; border-top: 1px solid var(--ink-100); padding-top: 10px; }
</style>
