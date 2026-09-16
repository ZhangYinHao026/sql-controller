<script setup>
import { ref, watch } from 'vue'
import { typeLabel } from '../utils/dbRegistry'

defineProps({ show: Boolean, connections: Array, loading: Boolean })
const emit = defineEmits(['close', 'add', 'edit', 'remove', 'test', 'search'])
const keyword = ref('')
watch(() => keyword.value, value => emit('search', value))
</script>

<template>
  <div v-if="show" class="modal-mask"><section class="modal wide">
    <header class="modal-header"><h2>设置 · 数据库连接</h2><button class="icon-btn" @click="$emit('close')">×</button></header>
    <div class="setting-tools"><input v-model="keyword" class="input search" placeholder="搜索省份" /><button class="btn primary" @click="$emit('add')">＋ 新增连接</button></div>
    <div class="table-wrap setting-list"><table class="data-table"><thead><tr><th>类型</th><th>省份 / 环境</th><th>主机</th><th>端口</th><th>数据库</th><th>用户名</th><th>密码</th><th>操作</th></tr></thead>
      <tbody><tr v-if="loading"><td colspan="8" class="empty-cell">正在加载…</td></tr><tr v-else-if="!connections.length"><td colspan="8" class="empty-cell">暂无连接配置</td></tr><tr v-for="row in connections" :key="row.id"><td>{{ typeLabel(row.dbType) }}</td><td>{{ row.regionName }}</td><td>{{ row.host }}</td><td>{{ row.port }}</td><td>{{ row.databaseName || '—' }}</td><td>{{ row.username }}</td><td>{{ row.password || '••••••••' }}</td><td class="actions"><button class="link-btn" @click="$emit('test', row)">测试连接</button><button class="link-btn" @click="$emit('edit', row)">编辑</button><button class="link-btn danger-text" @click="$emit('remove', row)">删除</button></td></tr></tbody>
    </table></div>
  </section></div>
</template>
