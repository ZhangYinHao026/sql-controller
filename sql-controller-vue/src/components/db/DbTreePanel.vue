<script setup>
/**
 * 左侧「表」面板：本地 / 线上 两个根 → 连接 → 表。
 *
 * 只负责「选表」这件事：双击表把 Tab 打开（state/tableTabs），数据由 TableWorkspace 自己加载。
 * 表清单按连接懒加载并缓存，点 ⟳ 才重拉（大库首拉可能几秒）。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../../api'
import { openTable, tableTabs, invalidateTab } from '../../state/tableTabs'
import { pushSql } from '../../state/sqlBridge'
import { DB_REGISTRY } from '../../utils/dbRegistry'

const keyword = ref('')
const loadingLocal = ref(false)
const error = ref('')
const notice = ref('')
const context = ref(null)

const roots = reactive([
  {key: 'local', label: '本地', note: 'JDBC 直连', open: true, connections: [], loading: false},
  {key: 'online', label: '线上 · 生产', note: '只读 / 可写', open: false, connections: [], loading: false}
])

const localRoot = computed(() => roots[0])
const onlineRoot = computed(() => roots[1])

function labelOf(dbType) {
  return DB_REGISTRY[dbType]?.label || dbType || ''
}

function decorate(connection) {
  return {
    ...connection,
    open: false,
    loading: false,
    error: '',
    tables: [],
    tablesAt: 0
  }
}

async function loadLocal(force = false) {
  if (loadingLocal.value) return
  loadingLocal.value = true
  error.value = ''
  try {
    const list = await api.connections() || []
    const previous = new Map(localRoot.value.connections.map(item => [item.id, item]))
    localRoot.value.connections = (Array.isArray(list) ? list : []).map(item => {
      const old = previous.get(item.id)
      return old ? Object.assign(old, item) : decorate(item)
    })
    if (force) localRoot.value.connections.forEach(item => (item.tablesAt = 0))
  } catch (e) {
    error.value = e.message
  } finally {
    loadingLocal.value = false
  }
}

async function loadOnline() {
  if (onlineRoot.value.loading) return
  onlineRoot.value.loading = true
  try {
    const list = await api.onlineConnections() || []
    onlineRoot.value.connections = (Array.isArray(list) ? list : []).map(item => ({
      id: item.id,
      dbType: item.dbType || 'mysql',
      regionName: item.displayName || item.regionName,
      databaseName: item.areaCode ? `区域 ${item.areaCode}` : '',
      enabled: item.enabled !== false,
      open: false,
      tables: [],
      tablesAt: 0,
      loading: false,
      error: ''
    }))
  } catch (e) {
    onlineRoot.value.connections = []
    notice.value = e.message
  } finally {
    onlineRoot.value.loading = false
  }
}

async function toggleConnection(connection, env) {
  connection.open = !connection.open
  if (connection.open && !connection.tablesAt) {
    await loadTables(connection, env)
  }
}

async function loadTables(connection, env) {
  if (connection.loading) return
  connection.loading = true
  connection.error = ''
  try {
    const list = env === 'online'
      ? await api.onlineDbTables(connection.id)
      : await api.dbTables(connection.id)
    connection.tables = Array.isArray(list) ? list : []
    connection.tablesAt = Date.now()
  } catch (e) {
    connection.error = e.message
    connection.tables = []
  } finally {
    connection.loading = false
  }
}

function prettyName(connection) {
  return connection.regionName || connection.databaseName || `连接 ${connection.id}`
}

function connectionText(connection) {
  return `${prettyName(connection)} ${connection.databaseName || ''} ${labelOf(connection.dbType)}`.toLowerCase()
}

function tableText(table) {
  return `${table.name || ''} ${table.comment || ''}`.toLowerCase()
}

function visibleTables(connection) {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return connection.tables
  return connection.tables.filter(table => tableText(table).includes(kw))
}

/** 关键字命中连接名时展示全部表；否则只展示命中的表；都没命中则该连接整行隐藏。 */
function connectionVisible(connection) {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return true
  if (connectionText(connection).includes(kw)) return true
  return connection.tables.some(table => tableText(table).includes(kw))
}

function isActive(connection, table, env) {
  return tableTabs.activeKey === `${env}:${connection.id}:${table.name}`
}

function openTableData(connection, table, env = 'local') {
  openTable({
    env,
    connectionId: connection.id,
    connectionName: prettyName(connection),
    dbType: connection.dbType,
    table: table.name,
    comment: table.comment || ''
  })
  context.value = null
}

async function copyName(table) {
  context.value = null
  try {
    await navigator.clipboard.writeText(table.name)
    notice.value = `已复制表名 ${table.name}`
  } catch (e) {
    notice.value = '复制失败：浏览器未授权剪贴板'
  }
}

/**
 * 右键「编辑表结构」：打开该表并直接落在结构页，结构加载完自动进编辑态。
 * 编辑请求走 tab.editRequest，由 TableStructurePanel 消费（它是 v-if 挂载的，直接调不到方法）。
 */
function openTableStructure(connection, table, env = 'local') {
  const tab = openTable({
    env,
    connectionId: connection.id,
    connectionName: prettyName(connection),
    dbType: connection.dbType,
    table: table.name,
    comment: table.comment || ''
  })
  tab.view = 'struct'
  tab.editRequest = Date.now()
  context.value = null
}

/**
 * 右键「生成 SELECT」：把基础查询塞进 SQL 编辑器，切回 SQL 工作台，不自动执行。
 * 标识符只给 MySQL 加反引号：达梦/神通未加引号时标识符折成大写，
 * 硬套双引号反而会把「原本能用」的表名变大小写敏感，查不到。
 */
function generateSelect(connection, table, env = 'local') {
  const name = table.name
  const target = connection.dbType === 'mysql' ? `\`${name}\`` : name
  pushSql(`SELECT *\nFROM ${target}\nLIMIT 100`)
  context.value = null
}

function showContext(connection, table, event, env) {
  context.value = {x: event.clientX, y: event.clientY, connection, table, env}
}

/** 连接级右键：没有 table，菜单只给「刷新该库」。 */
function showConnContext(connection, event, env) {
  context.value = {x: event.clientX, y: event.clientY, connection, table: null, env}
}

/** 作废某个连接下已打开的表 Tab（后端缓存清了，界面也得重拉）。 */
function invalidateConnectionTabs(connection, env) {
  tableTabs.tabs
    .filter(tab => tab.connectionId === connection.id && tab.env === env)
    .forEach(tab => invalidateTab(tab.key))
}

/**
 * 表级刷新：清后端这张表的结构缓存。结构缓存默认永不过期，这是它唯一的失效入口。
 * 已打开该表的 Tab 一起重拉，否则切回去还是旧结构。
 */
async function refreshTableStructure(connection, table, env) {
  context.value = null
  try {
    if (env === 'online') {
      await api.onlineDbCacheRefresh(connection.id, table.name)
    } else {
      await api.dbCacheRefresh(connection.id, table.name)
    }
    invalidateTab(`${env}:${connection.id}:${table.name}`)
    notice.value = `已刷新 ${table.name} 的结构`
  } catch (e) {
    notice.value = `刷新结构失败：${e.message}`
  }
}

/** 库级刷新：清该连接全部结构缓存（本地连带联想缓存），再重拉表清单。 */
async function refreshConnection(connection, env) {
  context.value = null
  try {
    if (env === 'online') {
      await api.onlineDbCacheRefresh(connection.id)
    } else {
      await api.dbCacheRefresh(connection.id)
    }
    invalidateConnectionTabs(connection, env)
    notice.value = `已刷新 ${prettyName(connection)} 的结构`
  } catch (e) {
    notice.value = `重拉结构失败：${e.message}`
  }
  connection.tablesAt = 0
  await loadTables(connection, env)
}

function toggleRoot(root) {
  root.open = !root.open
  if (root.key === 'online' && root.open && !root.connections.length) {
    loadOnline()
  }
}

onMounted(loadLocal)
</script>

<template>
  <div class="db-tree">
    <div class="db-tree-search">
      <input v-model="keyword" placeholder="搜索表名 / 连接" spellcheck="false"/>
      <button class="plain-icon" title="刷新连接与表清单" @click="loadLocal(true)">⟳</button>
    </div>

    <div v-if="error" class="db-tree-error">{{ error }}</div>

    <div v-for="root in roots" :key="root.key" class="db-root">
      <button class="db-root-head" @click="toggleRoot(root)">
        <span class="db-caret">{{ root.open ? '▾' : '▸' }}</span>
        <b>{{ root.label }}</b>
        <small>{{ root.note }}</small>
      </button>

      <div v-if="root.open" class="db-root-body">
        <div v-if="root.loading" class="command-empty">加载中…</div>
        <template v-else>
          <div v-for="connection in root.connections" v-show="connectionVisible(connection)"
               :key="`${root.key}-${connection.id}`" class="db-conn">
            <button class="db-conn-head" :class="{ online: root.key === 'online' }"
                    :title="prettyName(connection)"
                    @click="toggleConnection(connection, root.key)"
                    @contextmenu.prevent="showConnContext(connection, $event, root.key)">
              <span class="db-caret">{{ connection.open ? '▾' : '▸' }}</span>
              <span class="db-conn-name">{{ prettyName(connection) }}</span>
              <span class="db-conn-meta">{{ labelOf(connection.dbType) }}<template v-if="connection.databaseName"> · {{ connection.databaseName }}</template></span>
            </button>

            <div v-if="connection.open" class="db-tables">
              <div v-if="connection.loading" class="command-empty">读取表清单…</div>
              <div v-else-if="connection.error" class="db-tree-error">{{ connection.error }}</div>
              <div v-else-if="!visibleTables(connection).length" class="command-empty">
                {{ connection.tables.length ? '无匹配表' : '该库没有可浏览的表' }}
              </div>
              <button v-for="table in visibleTables(connection)" :key="table.name" class="db-table"
                      :class="{ active: isActive(connection, table, root.key) }"
                      :title="table.comment || table.name"
                      @dblclick="openTableData(connection, table, root.key)"
                      @contextmenu.prevent="showContext(connection, table, $event, root.key)">
                <span class="db-table-name">{{ table.name }}</span>
                <span v-if="table.comment" class="db-table-comment">{{ table.comment }}</span>
              </button>
            </div>
          </div>

          <div v-if="!root.connections.length" class="command-empty">
            {{ root.key === 'local' ? '暂无连接，请在设置中新增' : '暂无线上区域，请在设置 → 线上环境导入' }}
          </div>
        </template>
      </div>
    </div>

    <p class="db-tree-hint">双击表打开数据 · 右键更多操作</p>
    <p v-if="notice" class="db-tree-hint notice" @click="notice = ''">{{ notice }}</p>

    <div v-if="context" class="command-context db-context" :style="{ left: `${context.x}px`, top: `${context.y}px` }"
         @mouseleave="context = null">
      <template v-if="context.table">
        <button @click="openTableData(context.connection, context.table, context.env)">
          {{ context.env === 'online' ? '查看数据（生产）' : '查看数据' }}
          <em class="ctx-hint">双击</em>
        </button>
        <button @click="openTableStructure(context.connection, context.table, context.env)">编辑表结构</button>
        <div class="ctx-sep"></div>
        <button @click="generateSelect(context.connection, context.table, context.env)">生成 SELECT</button>
        <button @click="copyName(context.table)">复制表名</button>
        <button @click="refreshTableStructure(context.connection, context.table, context.env)">刷新</button>
      </template>
      <button v-else @click="refreshConnection(context.connection, context.env)">刷新该库</button>
    </div>
  </div>
</template>
