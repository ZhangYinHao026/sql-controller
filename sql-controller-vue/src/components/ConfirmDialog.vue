<script setup>
defineProps({
  show: Boolean,
  mode: { type: String, default: 'sql' },          // 'sql' | 'generic'
  title: { type: String, default: '' },
  message: { type: String, default: '' },
  sql: String,
  targets: { type: Array, default: () => [] },
  confirmText: { type: String, default: '' },
  cancelText: { type: String, default: '取消' },
  danger: { type: Boolean, default: true },
  warnings: { type: Array, default: () => [] }   // 转换告警：执行前提示被跳过的语句
})
defineEmits(['confirm', 'cancel'])
</script>
<template>
  <div v-if="show" class="modal-mask confirm-mask" @click.self="$emit('cancel')">
    <section class="modal" :class="{ compact: mode === 'sql', 'confirm-dialog': mode === 'generic' }">
      <header class="modal-header">
        <h2>{{ title || (mode === 'sql' ? '确认执行危险 SQL' : '请确认操作') }}</h2>
        <button class="icon-btn" @click="$emit('cancel')">×</button>
      </header>
      <p v-if="message" :class="danger ? 'risk' : ''">{{ message }}</p>
      <p v-else-if="mode === 'sql'" class="risk">该操作可能修改或删除所选数据库中的数据，请确认 SQL 内容无误。</p>
      <div v-if="mode === 'sql' && warnings.length" class="warn-strip">
        <div class="warn-title">⚠ 转换告警：以下 {{ warnings.length }} 条语句转换时被跳过，未包含在待执行脚本中</div>
        <ul><li v-for="(w, i) in warnings" :key="i">{{ w }}</li></ul>
      </div>
      <div v-if="mode === 'sql'" class="sql-preview">目标：{{ targets.map(item => item.regionName).join('、') }}\n\n{{ sql }}</div>
      <div class="modal-actions">
        <button class="btn" @click="$emit('cancel')">{{ cancelText }}</button>
        <button :class="danger ? 'btn danger' : 'btn primary'" @click="$emit('confirm')">{{ confirmText || (mode === 'sql' ? '确认执行' : '确认') }}</button>
      </div>
    </section>
  </div>
</template>