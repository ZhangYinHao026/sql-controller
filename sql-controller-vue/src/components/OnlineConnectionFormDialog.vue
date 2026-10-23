<script setup>
import { ref, watch, computed } from 'vue'
import { DB_REGISTRY, typeLabel } from '../utils/dbRegistry'

const props = defineProps({
  show: Boolean,
  connection: { type: Object, default: null },
  regions: { type: Array, default: () => [] },
  saving: Boolean
})
const emit = defineEmits(['close', 'save', 'test', 'load-regions', 'toast'])

const form = ref(empty())
/* 线上区域也要记库类型：后续「字段对比把本地结构同步到线上」要靠它决定 SQL 转换目标方言 */
const dbTypes = Object.keys(DB_REGISTRY)

function empty() {
  return { id: null, areaCode: '', regionName: '', dbType: 'mysql', displayName: '', ver: 'BT21', writeBaseUrl: '', enabled: true, remark: '' }
}

watch(() => [props.show, props.connection], () => {
  if (!props.show) return
  const c = props.connection
  form.value = c ? {
    id: c.id, areaCode: c.areaCode, regionName: c.regionName, dbType: c.dbType || 'mysql', displayName: c.displayName || '',
    ver: c.ver || 'BT21', writeBaseUrl: c.writeBaseUrl || '',
    enabled: c.enabled !== false, remark: c.remark || ''
  } : empty()
  emit('load-regions', form.value.ver)
}, { immediate: true })

const title = computed(() => (form.value.id ? '编辑线上区域' : '新增线上区域'))
const areaOptions = computed(() => props.regions || [])

function pickArea(e) {
  const area = Number(e.target.value)
  const hit = areaOptions.value.find(r => Number(r.area) === area)
  if (!hit) return
  form.value.areaCode = hit.area
  form.value.regionName = hit.name
  if (!form.value.displayName) form.value.displayName = ''
}

function changeVer() {
  emit('load-regions', form.value.ver)
}

function validate() {
  const f = form.value
  if (!f.areaCode || Number(f.areaCode) <= 0) return '区域码必须为正整数'
  if (!String(f.regionName || '').trim()) return '区域名称不能为空'
  return ''
}

function payload() {
  return {
    id: form.value.id,
    areaCode: Number(form.value.areaCode),
    regionName: String(form.value.regionName || '').trim(),
    dbType: form.value.dbType || 'mysql',
    displayName: String(form.value.displayName || '').trim() || null,
    ver: form.value.ver,
    writeBaseUrl: String(form.value.writeBaseUrl || '').trim() || null,
    enabled: !!form.value.enabled,
    remark: String(form.value.remark || '').trim() || null
  }
}

function onSave() {
  const err = validate()
  if (err) return emit('toast', err)
  emit('save', payload())
}

function onTest() {
  const err = validate()
  if (err) return emit('toast', err)
  emit('test', payload())
}
</script>

<template>
  <div v-if="show" class="modal-mask"><section class="modal">
    <header class="modal-header"><h2>{{ title }}</h2><button class="icon-btn" @click="$emit('close')">×</button></header>
    <div class="form-grid">
      <label>版本
        <select v-model="form.ver" class="input" @change="changeVer">
          <option value="BT21">BT21（2021-2026）</option>
          <option value="BT18">BT18</option>
          <option value="BF">BF（报废）</option>
        </select>
      </label>
      <label>从区域清单选择
        <select class="input" :value="form.areaCode" @change="pickArea">
          <option value="">— 手动填写区域码 —</option>
          <option v-for="r in areaOptions" :key="r.area" :value="r.area">{{ r.area }} · {{ r.name }}{{ r.jiajiemi ? '（加密）' : '' }}</option>
        </select>
      </label>
      <label>区域码 <span class="req">*</span>
        <input v-model="form.areaCode" class="input" type="number" min="1" placeholder="如 32" />
      </label>
      <label>区域名称 <span class="req">*</span>
        <input v-model="form.regionName" class="input" placeholder="如 江苏省" />
      </label>
      <label>数据库类型
        <select v-model="form.dbType" class="input" title="字段对比同步到线上时，用它决定 SQL 方言转换目标">
          <option v-for="t in dbTypes" :key="t" :value="t">{{ typeLabel(t) }}</option>
        </select>
      </label>
      <label>展示名（可选）
        <input v-model="form.displayName" class="input" placeholder="留空则用区域名称" />
      </label>
      <label class="span-2">写入 Base URL（该省 allowance 部署地址）
        <input v-model="form.writeBaseUrl" class="input" placeholder="如 http://58.17.26.37:8080 ；留空表示仅可查询" />
      </label>
      <label class="span-2">备注
        <input v-model="form.remark" class="input" placeholder="可选" />
      </label>
      <label class="check-line span-2"><input v-model="form.enabled" type="checkbox" /> 启用该区域（查询页可选）</label>
    </div>
    <p class="form-tip">查询统一经远程查询小助手（只读，末尾分号会自动去掉，多语句需拆分）；写入走该省 updateSql（SM4 加密，需二次确认）。</p>
    <footer class="modal-actions">
      <button class="btn test-left" :disabled="saving" @click="onTest">测试连通</button>
      <button class="btn" @click="$emit('close')">取消</button>
      <button class="btn primary" :disabled="saving" @click="onSave">{{ saving ? '保存中…' : '保存' }}</button>
    </footer>
  </section></div>
</template>
