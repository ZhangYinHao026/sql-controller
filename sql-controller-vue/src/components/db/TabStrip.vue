<script setup>
/**
 * 主区域顶部 Tab 条（布局 A）：SQL 工作台固定第一个，后面是已打开的表。
 *
 * Tab 条只在有表打开时渲染（见 App.vue），避免平时多出一条无用的空条。
 * 表 Tab / SQL 工作台都支持右键菜单：关闭、关闭其他、关闭右侧、关闭全部。
 */
import { computed, ref } from 'vue'
import { activateTab, closeTab, SQL_TAB_KEY, tableTabs } from '../../state/tableTabs'
import { dirtySummary } from '../../utils/rowDiff'

const tabs = computed(() => tableTabs.tabs)

/** 未提交项数（更新行 + 新增行 + 删除行）。 */
function pendingCount(tab) {
  return dirtySummary({edits: tab.edits, drafts: tab.drafts, deletes: tab.deletes}).total
}

function close(tab) {
  menu.value = null
  const count = pendingCount(tab)
  if (count > 0 && !window.confirm(`“${tab.table}” 还有 ${count} 项未提交的修改，确定关闭吗？`)) {
    return
  }
  closeTab(tab.key)
}

/* ---- 右键菜单 ---- */
const menu = ref(null)

function openMenu(tab, event) {
  menu.value = {tab, x: event.clientX, y: event.clientY}
}

/** 点击的 Tab 右侧的表 Tab（SQL 工作台在最前，右键它 = 全部表 Tab）。 */
function tabsRightOf(tab) {
  if (!tab) return tableTabs.tabs.slice()
  const index = tableTabs.tabs.findIndex(item => item.key === tab.key)
  return index < 0 ? [] : tableTabs.tabs.slice(index + 1)
}

/** 除点击的 Tab 以外的全部表 Tab（SQL 工作台右键「关闭其他」= 全部表 Tab）。 */
function otherTabs(tab) {
  if (!tab) return tableTabs.tabs.slice()
  return tableTabs.tabs.filter(item => item.key !== tab.key)
}

/** 批量关闭：有未提交改动时只弹一次确认（汇总条数），不逐个打扰。 */
function closeBatch(list) {
  if (!list.length) return
  const total = list.reduce((sum, tab) => sum + pendingCount(tab), 0)
  if (total > 0 && !window.confirm(`即将关闭 ${list.length} 个标签，其中 ${total} 项未提交的修改会被丢弃，确定吗？`)) {
    return
  }
  list.forEach(tab => closeTab(tab.key))
}

function runMenu(action) {
  const tab = menu.value?.tab
  menu.value = null
  if (action === 'close') closeBatch([tab])
  else if (action === 'others') closeBatch(otherTabs(tab))
  else if (action === 'right') closeBatch(tabsRightOf(tab))
  else if (action === 'all') closeBatch(tableTabs.tabs.slice())
}
</script>

<template>
  <div class="tab-strip" @click="menu = null">
    <button class="strip-tab sql" :class="{ active: tableTabs.activeKey === SQL_TAB_KEY }"
            @contextmenu.prevent.stop="openMenu(null, $event)"
            @click="activateTab(SQL_TAB_KEY)">SQL 工作台
    </button>
    <button v-for="tab in tabs" :key="tab.key" class="strip-tab"
            :class="{ active: tableTabs.activeKey === tab.key }"
            :title="`${tab.connectionName || ''} · ${tab.table}`"
            @contextmenu.prevent.stop="openMenu(tab, $event)"
            @click="activateTab(tab.key)">
      <span v-if="tab.env === 'online'" class="strip-env">线上</span>
      <span class="strip-name">{{ tab.table }}</span>
      <span class="strip-dot" v-if="pendingCount(tab) > 0" :title="`${pendingCount(tab)} 项未提交`"></span>
      <span class="strip-close" role="button" :title="'关闭'" @click.stop="close(tab)">×</span>
    </button>
    <div v-if="menu" class="tab-menu" :style="{ left: `${menu.x}px`, top: `${menu.y}px` }" @mouseleave="menu = null">
      <button v-if="menu.tab" @click.stop="runMenu('close')">关闭</button>
      <button :disabled="!otherTabs(menu.tab).length" @click.stop="runMenu('others')">关闭其他标签</button>
      <button :disabled="!tabsRightOf(menu.tab).length" @click.stop="runMenu('right')">关闭右侧标签</button>
      <button :disabled="!tabs.length" @click.stop="runMenu('all')">关闭全部标签</button>
    </div>
  </div>
</template>
