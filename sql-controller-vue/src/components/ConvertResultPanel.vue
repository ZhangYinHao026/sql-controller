<script setup>
import { computed, ref, watch } from 'vue'
import { highlightSql } from '../utils/sqlHighlight'

/**
 * 单方言转换结果面板：按方言元数据着色，内部含双层高亮代码框（可编辑）。
 * 供 ConvertDialog 在「并排 grid / 页签 single」两种视图下复用。
 */
const props = defineProps({
    db: { type: Object, required: true },          // 方言元数据（key/name/hint/dot/bg/ink）
    modelValue: { type: String, default: '' },     // 转换后的目标方言文本（v-model）
    warnings: { type: Array, default: () => [] },
    status: { type: String, default: 'idle' },     // idle | loading | done | error
    error: { type: String, default: '' },
    ms: { type: Number, default: 0 }
})
const emit = defineEmits(['update:modelValue', 'copy', 'use'])

const highlight = computed(() => highlightSql(props.modelValue))
const code = ref(null)

const STATUS_TEXT = { idle: '待转换', loading: '转换中…', done: '已转换', error: '失败' }
const statusText = computed(() => STATUS_TEXT[props.status] || '待转换')

function onInput(e) {
    emit('update:modelValue', e.target.value)
}
function syncScroll(e) {
    const box = e.currentTarget.closest('.rp-code')
    const pre = box?.querySelector('.rp-hl')
    if (pre) {
        pre.scrollTop = e.currentTarget.scrollTop
        pre.scrollLeft = e.currentTarget.scrollLeft
    }
}
function onCopy() {
    if (props.modelValue) emit('copy', props.modelValue)
}
function onUse() {
    if (props.modelValue) emit('use', props.db.key)
}

// 展示 loading 时重置滚动位置到顶
watch(() => props.status, s => {
    if (s === 'loading' && code.value) {
        code.value.scrollTop = 0
        const pre = code.value.parentElement?.querySelector('.rp-hl')
        if (pre) pre.scrollTop = 0
    }
})
</script>

<template>
  <article class="rp" :style="{ '--dot': db.dot, '--bg': db.bg, '--ink': db.ink }">
    <header class="rp-head">
      <span class="rp-dot"></span>
      <b class="rp-name">{{ db.name }}</b>
      <span class="rp-hint">{{ db.hint }}</span>
      <span class="rp-status" :class="status">
        <span v-if="status === 'loading'" class="spinner"></span>{{ statusText }}
      </span>
      <div class="rp-acts">
        <button class="btn small" :disabled="!modelValue || status === 'loading'" @click="onCopy">复制</button>
        <button class="btn small primary" title="回填编辑器并立即执行"
                :disabled="!modelValue || status === 'loading'" @click="onUse">▶ 执行</button>
      </div>
    </header>

    <ul v-if="warnings.length" class="rp-warns">
      <li v-for="(w, i) in warnings" :key="i">⚠ {{ w }}</li>
    </ul>
    <div v-if="status === 'error'" class="rp-error">⚠ {{ error || '转换失败，请重试' }}</div>

    <div ref="code" class="rp-code">
      <template v-if="status === 'loading'">
        <div class="rp-skeleton">
          <i v-for="n in 5" :key="n" :style="{ width: (100 - n * 9) + '%' }"></i>
        </div>
      </template>
      <template v-else>
        <pre class="rp-hl" aria-hidden="true" v-html="highlight"></pre>
        <textarea class="rp-ta" spellcheck="false" :value="modelValue"
                  :disabled="status !== 'done' && status !== 'idle'"
                  @input="onInput" @scroll="syncScroll"
                  :placeholder="status === 'idle' ? '—— 点击「一键转换」生成 ——' : ''"></textarea>
      </template>
    </div>
    <footer v-if="status === 'done'" class="rp-foot">{{ ms ? `耗时 ${ms}ms` : '' }} · 结果可编辑</footer>
  </article>
</template>

<style scoped>
.rp {
    display: flex;
    flex-direction: column;
    border: 1px solid var(--line);
    border-radius: var(--r-md);
    background: #fff;
    overflow: hidden;
    min-width: 0;
}
.rp-head {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 9px 12px;
    background: var(--bg, var(--mint-50));
    border-bottom: 1px solid rgba(0, 0, 0, .05);
}
.rp-dot { width: 9px; height: 9px; border-radius: 50%; background: var(--dot, var(--mint-400)); flex: 0 0 9px; }
.rp-name { font-size: 13px; font-weight: 800; color: var(--ink-900); }
.rp-hint { font-size: 11px; color: var(--ink-500); font-weight: 500; }
.rp-status {
    margin-left: 4px;
    font-size: 10.5px;
    font-weight: 800;
    padding: 2px 8px;
    border-radius: var(--r-pill);
    background: #fff;
    border: 1px solid var(--line);
    color: var(--ink-300);
    display: inline-flex;
    align-items: center;
    gap: 5px;
}
.rp-status.done { color: var(--mint-600); border-color: var(--mint-200); background: #fff; }
.rp-status.error { color: #C96080; border-color: #F4C0D1; background: #FFF; }
.rp-status.loading { color: #C77B00; border-color: #F5D9A8; background: #FFF8E5; }
.rp-status .spinner {
    width: 9px; height: 9px;
    border: 1.5px solid #F5D9A8;
    border-top-color: #C77B00;
    border-radius: 50%;
    animation: rp-spin .8s linear infinite;
}
@keyframes rp-spin { to { transform: rotate(360deg); } }
.rp-acts { margin-left: auto; display: flex; gap: 6px; }

.rp-warns { list-style: none; padding: 8px 12px 0; margin: 0; }
.rp-warns li {
    font-size: 11px; color: #8a5d00; background: #FFF8E5;
    border-left: 3px solid #E0A100;
    padding: 3px 8px; margin-bottom: 5px; border-radius: 0 6px 6px 0; line-height: 1.6;
}
.rp-error { margin: 8px 12px 0; font-size: 12px; color: #B95A5A; background: #FDF0F0; border-radius: 8px; padding: 5px 10px; }

.rp-code { position: relative; flex: 1; min-height: 130px; display: flex; }
.rp-hl, .rp-ta {
    font-family: var(--font-mono);
    font-size: 12.5px;
    line-height: 20px;
    padding: 11px 13px;
    margin: 0;
    box-sizing: border-box;
    white-space: pre-wrap;
    word-break: break-word;
    tab-size: 4;
}
.rp-hl {
    position: absolute; inset: 0;
    background: linear-gradient(180deg, #FFFDF7, #FFF8E8);
    color: var(--ink-900);
    overflow: hidden;
    pointer-events: none;
    border: 0;
}
.rp-ta {
    flex: 1; width: 100%;
    background: transparent;
    color: transparent;
    caret-color: var(--ink-900);
    border: 0; outline: none;
    resize: none;
    overflow: auto;
    position: relative;
}
.rp-ta:disabled { color: var(--ink-300); caret-color: transparent; }
.rp-ta::placeholder { color: var(--ink-300); }
.rp-ta:focus { box-shadow: inset 0 0 0 2px rgba(107, 206, 178, .35); border-radius: 0; }
.rp-ta::selection { background: rgba(107, 206, 178, .35); color: transparent; }
.rp-hl :deep(.sql-string) { color: #4FA377; }
.rp-hl :deep(.sql-comment) { color: var(--ink-300); font-style: italic; }
.rp-hl :deep(.sql-number) { color: #B07AC9; }

.rp-skeleton { flex: 1; padding: 13px; display: flex; flex-direction: column; gap: 9px; background: linear-gradient(180deg, #FFFDF7, #FFF8E8); }
.rp-skeleton i { height: 9px; border-radius: 5px; background: var(--mint-100); animation: rp-br 1.1s ease-in-out infinite; }
.rp-skeleton i:nth-child(2) { animation-delay: .12s; }
.rp-skeleton i:nth-child(3) { animation-delay: .24s; }
.rp-skeleton i:nth-child(4) { animation-delay: .36s; }
.rp-skeleton i:nth-child(5) { animation-delay: .48s; }
@keyframes rp-br { 0%, 100% { opacity: .45; } 50% { opacity: 1; } }

.rp-foot {
    padding: 6px 12px;
    font-size: 10.5px;
    color: var(--ink-300);
    background: #FFFDF8;
    border-top: 1px solid var(--line);
    text-align: right;
}
</style>
