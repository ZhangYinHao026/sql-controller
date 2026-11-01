<script setup>
import { ref, watch } from 'vue'
import { typeLabel } from '../utils/dbRegistry'

const props = defineProps({
  show: Boolean, connections: Array, loading: Boolean,
  onlineConnections: { type: Array, default: () => [] }, onlineLoading: Boolean,
  onlineStatus: { type: Object, default: null }
})
const emit = defineEmits(['close', 'add', 'edit', 'remove', 'test', 'search',
  'online-add', 'online-edit', 'online-remove', 'online-test', 'online-import', 'online-search', 'tab'])

const tab = ref('local')
const keyword = ref('')
const onlineKeyword = ref('')
watch(() => keyword.value, value => emit('search', value))
watch(() => onlineKeyword.value, value => emit('online-search', value))

function switchTab(next) {
  if (tab.value === next) return
  tab.value = next
  emit('tab', next)
}

function onlineState(row) {
  if (!row.writeBaseUrl) return { text: '仅可查', cls: 'muted' }
  return props.onlineStatus?.writeEnabled
    ? { text: '可查可写', cls: 'ok' }
    : { text: '可查（写入未启用）', cls: 'warn' }
}

</script>

<template>
  <div v-if="show" class="modal-mask"><section class="modal wide">
    <header class="modal-header"><h2>设置 · 数据库连接</h2><button class="icon-btn" @click="$emit('close')">×</button></header>
    <div class="setting-tabs">
      <button type="button" class="setting-tab" :class="{ on: tab === 'local' }" @click="switchTab('local')">本地环境</button>
      <button type="button" class="setting-tab" :class="{ on: tab === 'online' }" @click="switchTab('online')">
        线上环境 <small v-if="onlineConnections.length">({{ onlineConnections.length }})</small>
      </button>
    </div>

    <template v-if="tab === 'local'">
      <div class="setting-tools"><input v-model="keyword" class="input search" placeholder="搜索省份" /><button class="btn primary" @click="$emit('add')">＋ 新增连接</button></div>
      <div class="table-wrap setting-list"><table class="data-table"><thead><tr><th>类型</th><th>省份 / 环境</th><th>主机</th><th>端口</th><th>数据库</th><th>用户名</th><th>密码</th><th>操作</th></tr></thead>
        <tbody><tr v-if="loading"><td colspan="8" class="empty-cell">正在加载…</td></tr><tr v-else-if="!connections.length"><td colspan="8" class="empty-cell">暂无连接配置</td></tr><tr v-for="row in connections" :key="row.id"><td>{{ typeLabel(row.dbType) }}</td><td>{{ row.regionName }}</td><td>{{ row.host }}</td><td>{{ row.port }}</td><td>{{ row.databaseName || '—' }}</td><td>{{ row.username }}</td><td>{{ row.password || '••••••••' }}</td><td class="actions"><button class="link-btn" @click="$emit('test', row)">测试连接</button><button class="link-btn" @click="$emit('edit', row)">编辑</button><button class="link-btn danger-text" @click="$emit('remove', row)">删除</button></td></tr></tbody>
      </table></div>
    </template>

    <template v-else>
      <div class="setting-tools">
        <input v-model="onlineKeyword" class="input search" placeholder="搜索区域 / 区域码" />
        <button class="btn" title="从 pageConfig.json 拉取全部 java 区域（已存在的跳过）" @click="$emit('online-import')">⟳ 导入 java 区域</button>
        <button class="btn primary" @click="$emit('online-add')">＋ 新增线上区域</button>
      </div>
      <div class="table-wrap setting-list"><table class="data-table">
        <thead><tr><th>区域码</th><th>区域</th><th>类型</th><th>版本</th><th>写入接口</th><th>状态</th><th>备注</th><th>操作</th></tr></thead>
        <tbody>
        <tr v-if="onlineLoading"><td colspan="8" class="empty-cell">正在加载…</td></tr>
        <tr v-else-if="!onlineConnections.length"><td colspan="8" class="empty-cell">暂无线上区域，可点「⟳ 导入 java 区域」一键生成</td></tr>
        <tr v-for="row in onlineConnections" :key="row.id">
          <td>{{ row.areaCode }}</td>
          <td>{{ row.displayName || row.regionName }}</td>
          <td>{{ typeLabel(row.dbType) }}</td>
          <td>{{ row.ver }}</td>
          <td class="online-url">{{ row.writeBaseUrl || '—' }}</td>
          <td><span class="online-state" :class="onlineState(row).cls">{{ onlineState(row).text }}</span></td>
          <td>{{ row.remark || '—' }}</td>
          <td class="actions">
            <button class="link-btn" @click="$emit('online-test', row)">测试</button>
            <button class="link-btn" @click="$emit('online-edit', row)">编辑</button>
            <button class="link-btn danger-text" @click="$emit('online-remove', row)">删除</button>
          </td>
        </tr>
        </tbody>
      </table></div>
    </template>
  </section></div>
</template>
