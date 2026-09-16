<script setup>
import { computed, reactive, watch } from 'vue'
import { DB_REGISTRY, jdbcUrlOf, defaultPortOf, UNKNOWN_JDBC_PREFIX } from '../utils/dbRegistry'

const props = defineProps({ show: Boolean, connection: Object })
const emit = defineEmits(['close', 'save', 'test'])
const blank = () => ({ dbType: 'mysql', regionName: '', host: '192.168.0.227', port: defaultPortOf('mysql'), databaseName: '', username: '', password: '', remark: '' })
const form = reactive(blank())
const editing = computed(() => Boolean(props.connection?.id))
/* JDBC 预览统一由注册表生成：未知类型显式提示「未注册」，杜绝按错驱动兜底拼 URL */
const jdbcUrl = computed(() => {
  const host = form.host || 'host'
  const db = form.databaseName || 'database'
  const url = jdbcUrlOf(form.dbType, { host, port: form.port, database: db })
  return url ?? `${UNKNOWN_JDBC_PREFIX}：${form.dbType || '（未选择）'}`
})
watch(() => props.show, show => { if (show) Object.assign(form, props.connection ? { ...blank(), ...props.connection } : blank()) })
function setType() {
  const p = defaultPortOf(form.dbType)
  if (p) form.port = p   // 已知类型切默认端口；未注册类型保留用户输入不清空
}
function save() { emit('save', { id: props.connection?.id, body: { ...form, port: Number(form.port), jdbcUrl: jdbcUrl.value } }) }
function test() { emit('test', { ...form, port: Number(form.port), jdbcUrl: jdbcUrl.value }) }
</script>
<template>
  <div v-if="show" class="modal-mask"><section class="modal form-modal">
    <header class="modal-header"><h2>{{ editing ? '编辑数据库连接' : '新增数据库连接' }}</h2><button class="icon-btn" @click="$emit('close')">×</button></header>
    <form class="connection-form standalone" @submit.prevent="save"><div class="form-grid">
      <label>数据库类型<select v-model="form.dbType" @change="setType"><option v-for="(meta, key) in DB_REGISTRY" :key="key" :value="key">{{ meta.label }}</option></select></label><label>省份 / 环境<input v-model.trim="form.regionName" required /></label>
      <label>主机<input v-model.trim="form.host" placeholder="例如：192.168.0.227" required /></label><label>端口<input v-model.number="form.port" type="number" min="1" max="65535" required /></label>
      <label>数据库名<input v-model.trim="form.databaseName" required /></label><label>用户名<input v-model.trim="form.username" required /></label>
      <label class="span-2">密码<input v-model="form.password" type="text" required placeholder="请输入数据库密码" /></label>
      <label class="span-2">JDBC 地址 <output class="jdbc-output">{{ jdbcUrl }}</output></label><label class="span-2">备注<input v-model.trim="form.remark" /></label>
    </div><div class="modal-actions"><button type="button" class="btn" @click="$emit('close')">取消</button><button type="button" class="btn" @click="test">测试连接</button><button class="btn primary">保存连接</button></div></form>
  </section></div>
</template>
