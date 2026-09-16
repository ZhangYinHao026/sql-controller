<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  show: Boolean,
  items: Array,
  loading: Boolean,
  page: { type: Number, default: 1 },
  totalPages: { type: Number, default: 1 },
  hasNext: { type: Boolean, default: false },
  pageSize: { type: Number, default: 10 },
  total: { type: Number, default: 0 }
})
defineEmits(['close', 'page', 'page-size-change'])

const filter = ref('all')
const listWrap = ref(null)

/* 翻页后滚回列表顶部，避免停留旧位置 */
watch(() => props.page, () => {
  if (listWrap.value) listWrap.value.scrollTop = 0
})

/* 把 ISO 时间字符串（如 2026-08-10T15:49:16.000+00:00）格式化为 YYYY-MM-DD HH:mm:ss */
function formatTime(s) {
  if (!s) return ''
  const m = String(s).match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})/)
  return m ? `${m[1]}-${m[2]}-${m[3]} ${m[4]}:${m[5]}:${m[6]}` : s
}

/* 分类：按 sqlType 判断是查询还是修改 */
const QUERY_TYPES = ['SELECT', 'SHOW', 'DESC', 'DESCRIBE', 'WITH']
const MODIFY_TYPES = ['INSERT', 'UPDATE', 'DELETE', 'CREATE', 'ALTER', 'DROP', 'TRUNCATE', 'RENAME']
function category(type) {
  const t = String(type || '').toUpperCase()
  if (QUERY_TYPES.includes(t)) return 'query'
  if (MODIFY_TYPES.includes(t)) return 'modify'
  return 'other'
}
const counts = computed(() => {
  const c = { all: 0, query: 0, modify: 0 }
  for (const it of props.items || []) {
    c.all++
    if (category(it.sqlType) === 'query') c.query++
    else if (category(it.sqlType) === 'modify') c.modify++
  }
  return c
})
const filtered = computed(() => {
  if (filter.value === 'all') return props.items || []
  return (props.items || []).filter(it => category(it.sqlType) === filter.value)
})
</script>
<template>
  <div v-if="show" class="modal-mask" @click.self="$emit('close')">
    <section class="modal history-modal">
      <header class="modal-header"><h2>执行历史</h2><button class="icon-btn" @click="$emit('close')">×</button></header>

      <Transition name="fade">
        <div v-if="loading" class="history-loading">正在加载…</div>
      </Transition>
      <template v-if="items.length">
        <div class="history-filter">
          <button class="filter-chip" :class="{active: filter==='all'}" @click="filter='all'">全部 <span class="cnt">{{ counts.all }}</span></button>
          <button class="filter-chip" :class="{active: filter==='query'}" @click="filter='query'">🔍 查询 <span class="cnt">{{ counts.query }}</span></button>
          <button class="filter-chip" :class="{active: filter==='modify'}" @click="filter='modify'">✏ 修改 <span class="cnt">{{ counts.modify }}</span></button>
          <span v-if="total >= 0" class="muted total-tip">共 {{ total }} 条</span>
        </div>

        <!-- 中间列表：翻页时保留旧列表（半透明），避免闪烁 -->
        <div ref="listWrap" class="history-list-wrap" :class="{ loading: loading }">
          <div v-if="!filtered.length" class="empty small-empty">当前分类暂无记录</div>
          <article v-for="item in filtered" :key="item.id" class="history-item">
            <div class="history-item-head">
              <span class="history-region">{{ item.regionName }} · {{ item.sqlType }}</span>
              <span :class="item.status === 'SUCCESS' ? 'status ok' : 'status failed'">{{ item.status === 'SUCCESS' ? '成功' : '失败' }}</span>
              <span class="history-meta">{{ formatTime(item.createdAt) }} · {{ item.durationMs }} ms · 影响 {{ item.affectedRows }} 行</span>
            </div>
            <code>{{ item.sqlText }}</code>
            <small v-if="item.errorMessage" class="history-err" :title="item.errorMessage">{{ item.errorMessage }}</small>
          </article>
        </div>

        <div class="history-pager">
          <button class="btn small" :disabled="loading || page <= 1" @click="$emit('page', page - 1)">上一页</button>
          <span class="page-indicator">第 {{ page }} / {{ totalPages }} 页</span>
          <button class="btn small" :disabled="loading || !hasNext" @click="$emit('page', page + 1)">下一页</button>
          <label class="pagination-control">每页 <input class="small-input" type="number" min="1" max="100" :value="pageSize"
                                                       @change="$emit('page-size-change', $event.target.value)"> 条</label>
        </div>
      </template>
      <div v-else-if="!loading" class="empty">暂无执行历史</div>

      <div class="modal-actions"><button class="btn" @click="$emit('close')">关闭</button></div>
    </section>
  </div>
</template>

<style scoped>
.history-filter {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 0 12px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 4px;
  flex: 0 0 auto;
}
.filter-chip {
  border: 1.5px solid var(--ink-100);
  border-radius: var(--r-pill);
  padding: 4px 12px;
  font-size: 12.5px;
  font-weight: 600;
  background: var(--surface);
  color: var(--ink-700);
  transition: all var(--t-fast) var(--ease);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.filter-chip:hover {
  border-color: var(--mint-300);
  color: var(--mint-700);
  transform: translateY(-1px);
}
.filter-chip.active {
  background: var(--mint-100);
  border-color: var(--mint-300);
  color: var(--mint-700);
  box-shadow: 0 2px 8px rgba(107, 206, 178, .25);
}
.filter-chip .cnt {
  font-size: 11px;
  background: var(--ink-50);
  color: var(--ink-500);
  padding: 0 7px;
  border-radius: var(--r-pill);
  line-height: 16px;
  font-weight: 700;
}
.filter-chip.active .cnt { background: var(--surface); color: var(--mint-700); }
.total-tip { margin-left: auto; padding: 0 12px; }

/* 加载浮层：绝对定位覆盖，不占布局（避免弹窗内容高度变化导致抖动） */
.history-loading {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background: rgba(255, 255, 255, .55);
  color: var(--ink-500);
  font-size: 13px;
  font-weight: 600;
  z-index: 5;
  border-radius: var(--r-lg);
  pointer-events: none;
}
.fade-enter-active,
.fade-leave-active {
  transition: opacity .18s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
.history-list-wrap {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  margin: 0 -24px;
  padding: 4px 24px;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  transition: opacity var(--t-fast) var(--ease);
}
.history-list-wrap.loading {
  opacity: .5;
  pointer-events: none;
}
.history-list-wrap .empty.small-empty { min-height: 120px; }

.history-item {
  padding: 8px 0;
  border-bottom: 1px solid var(--ink-100);
}
.history-item:last-child { border-bottom: 0; }
.history-item code {
  display: block;
  margin: 4px 0 0;
  font: 12px/1.45 var(--font-mono);
  color: var(--ink-700);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  padding: 4px 10px;
  background: var(--ink-50);
  border-radius: var(--r-xs);
}
/* 错误信息：固定 3 行截断（避免长文本逐字断行导致滚动卡顿），hover 显示完整内容 */
.history-item small.history-err {
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-top: 4px;
  font-size: 11.5px;
  color: #B95A5A;
  background: #FFF5F5;
  padding: 6px 10px;
  border-radius: var(--r-xs);
  border-left: 3px solid var(--danger);
  cursor: help;
}
.history-item-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.history-region {
  font-weight: 700;
  font-size: 13px;
  color: var(--ink-900);
}
.history-meta {
  font-size: 11.5px;
  color: var(--ink-500);
  margin-left: auto;
}
.history-pager {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 0 0;
  border-top: 1px solid var(--line);
  margin-top: 12px;
  flex: 0 0 auto;
}
.history-pager .page-indicator {
  min-width: 108px;
  text-align: center;
  white-space: nowrap;
}
.history-pager .pagination-control { margin-left: auto; }
</style>

<style>
/* 弹窗：固定高度（内容多少都不变 → 居中位置/分页条位置稳定，不抖动），列表区内部滚动 */
.modal.history-modal {
  position: relative;
  height: min(92vh, 620px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 20px 24px 16px;
}
</style>