<script setup>
/**
 * 字段差异 · 单行（基准列 / 同步动作 / 比对列）
 * 勾选态从 diffState[stateKey] 读取，依赖收集只发生在「本行组件」——
 * 勾任意一列只重渲染该行，不再触发整表 detailRows 重建。
 *
 * drop 行（基准无 / 对比多）提供两个互斥动作：
 *   1) 'drop' 生成 DROP —— 删掉对比库该列（对齐基准库）
 *   2) 'back' 补基准库 ADD —— 保留对比库字段，反向补到基准库（脚本目标=基准库）
 */
import { computed } from 'vue'

const props = defineProps({
  row: { type: Object, required: true },   // {name, kind, reasons, l, r, chip, metaL, metaR, stateKey}
  state: { type: Object, required: true }  // 父级 diffState（reactive 引用）
})
const emit = defineEmits(['set-choice'])

/* 各动作的勾选态：add 默认勾上（st !== 'skip'）；drop / back / mod 需显式等于对应值 */
const addOn = computed(() => props.state[props.row.stateKey] !== 'skip')
const dropOn = computed(() => props.state[props.row.stateKey] === 'drop')
const backOn = computed(() => props.state[props.row.stateKey] === 'back')
const modOn = computed(() => props.state[props.row.stateKey] === 'mod')

function toggle(kind, e) {
  emit('set-choice', { name: props.row.name, kind, on: e.target.checked })
}
</script>

<template>
  <div class="diff-row" :class="row.kind === 'same' ? 'same' : row.kind">
    <div v-if="row.l" class="diff-cell">
      <div class="diff-fname">{{ row.l.name }}</div>
      <div class="diff-fmeta"><span v-for="(m, i) in row.metaL" :key="i"><span v-if="m.k === 'pk'" class="pk-tag">{{ m.v }}</span><span v-else-if="m.k === 'type'" class="type-tag" :title="'字段类型 ' + m.v">{{ m.v }}</span><template v-else>{{ m.v }}</template> </span></div>
    </div>
    <div v-else class="diff-cell diff-empty-cell">— 无此字段 —</div>

    <div class="diff-cell diff-mid">
      <span class="mid-kind" :class="row.kind === 'same' ? 'ok' : row.kind">{{ row.chip }}</span>
      <label v-if="row.kind === 'add'" class="diff-colchk">
        <input type="checkbox" :checked="addOn" @change="toggle('add', $event)">生成 ADD
      </label>
      <template v-else-if="row.kind === 'drop'">
        <label class="diff-colchk">
          <input type="checkbox" :checked="backOn" @change="toggle('back', $event)"
                 title="保留对比库该字段，改为在基准库补充（脚本目标=基准库）">补基准库 ADD
        </label>
        <label class="diff-colchk danger">
          <input type="checkbox" :checked="dropOn" @change="toggle('drop', $event)">生成 DROP（危险）
        </label>
      </template>
      <label v-else-if="row.kind === 'alt'" class="diff-colchk">
        <input type="checkbox" :checked="modOn" @change="toggle('mod', $event)">生成 MODIFY（对齐基准）
      </label>
      <div v-if="row.reasons.length" class="mid-reason">{{ row.reasons.join('；') }}</div>
    </div>

    <div v-if="row.r" class="diff-cell">
      <div class="diff-fname">{{ row.r.name }}</div>
      <div class="diff-fmeta"><span v-for="(m, i) in row.metaR" :key="i"><span v-if="m.k === 'pk'" class="pk-tag">{{ m.v }}</span><span v-else-if="m.k === 'type'" class="type-tag" :title="'字段类型 ' + m.v">{{ m.v }}</span><template v-else>{{ m.v }}</template> </span></div>
    </div>
    <div v-else class="diff-cell diff-empty-cell">— 无此字段 —</div>
  </div>
</template>

<style scoped>
.diff-row { display: grid; grid-template-columns: 1fr 190px 1fr; border-bottom: 1px solid var(--ink-100); }
.diff-row:last-child { border-bottom: 0; }
.diff-cell { padding: 7px 12px; font-size: 12px; min-height: 38px; display: flex; flex-direction: column; align-items: flex-start; justify-content: center; gap: 2px; color: var(--ink-700); }
.diff-cell + .diff-cell { border-left: 1px solid var(--ink-100); }
.diff-row.same { background: var(--mint-50); }
.diff-row.add { background: #FFFBF2; }
.diff-row.drop { background: #FFF5F6; }
.diff-row.alt { background: #F5F9FF; }
.diff-row.pk { background: #FBF6FE; }
.diff-fname { font-weight: 800; color: var(--ink-900); font-family: var(--font-mono); font-size: 12.5px; }
.diff-fmeta { font-size: 11px; color: var(--ink-500); }
.diff-fmeta .pk-tag { color: var(--mint-600); font-weight: 800; }
.diff-fmeta .type-tag { font-family: var(--font-mono); color: var(--ink-700); background: var(--surface-1); padding: 0 6px; border-radius: var(--r-pill); margin-right: 4px; }
.diff-empty-cell { justify-content: center; color: var(--ink-300); font-size: 12px; font-style: italic; }
.diff-mid { display: flex; flex-direction: column; justify-content: center; align-items: stretch; gap: 6px; padding: 6px 10px; }
.mid-kind { font-size: 11px; font-weight: 800; border-radius: var(--r-pill); padding: 2px 10px; text-align: center; }
.mid-kind.ok { background: var(--mint-100); color: var(--mint-700); }
.mid-kind.add { background: #FDEBD9; color: #A76A12; }
.mid-kind.drop { background: #FFE4EC; color: #C2525F; }
.mid-kind.alt { background: #E3EEFB; color: #3E6DA8; }
.mid-kind.pk { background: #F1E7F7; color: #83539E; }
.mid-reason { font-size: 10.5px; color: var(--ink-500); line-height: 1.5; }
.diff-colchk { display: flex; align-items: center; gap: 5px; font-size: 11.5px; color: var(--ink-700); cursor: pointer; user-select: none; }
.diff-colchk input { accent-color: var(--mint-500); margin: 0; }
.diff-colchk.danger { color: #C2525F; font-weight: 700; }
</style>
