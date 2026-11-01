<script setup>
/**
 * 变更条：网格下方浮出的「N 处修改待提交」。
 *
 * 只负责展示与两个动作（撤销 / 提交），真正的 dryRun → 确认 → 执行链路在 TableWorkspace，
 * 这样切 Tab 时状态仍挂在 tab 上，不会因为组件销毁丢失。
 */
import { computed } from 'vue'
import { dirtySummary } from '../../utils/rowDiff'

const props = defineProps({
  tab: {type: Object, required: true},
  submitting: {type: Boolean, default: false}
})
const emit = defineEmits(['submit', 'revert'])

const summary = computed(() => dirtySummary({
  edits: props.tab.edits,
  drafts: props.tab.drafts,
  deletes: props.tab.deletes
}))
</script>

<template>
  <div v-if="summary.total" class="edit-bar">
    <span class="edit-dot"></span>
    <b>{{ summary.total }} 项待提交</b>
    <span class="edit-desc">{{ summary.description }}</span>
    <button class="link-btn" :disabled="submitting" @click="emit('revert')">撤销全部</button>
    <button class="btn small primary" :disabled="submitting" @click="emit('submit')">
      {{ submitting ? '提交中…' : '提交变更' }}
    </button>
  </div>
</template>
