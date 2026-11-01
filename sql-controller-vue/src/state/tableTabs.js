/**
 * 表浏览 Tab 状态（布局 A：表视图与 SQL 编辑器同级多 Tab）。
 *
 * 为什么不用 vue-router：项目现在没有路由，表视图只是「主区域的一种呈现」，
 * 用完即关；用模块级 reactive 单例即可，App.vue 只负责挂载。
 *
 * 每个 Tab 自己保存分页 / 排序 / 筛选 / 已加载的数据，切换 Tab 不会重新请求，
 * 也不会丢滚动位置（数据在内存，DOM 由 v-show 保持）。
 */
import { computed, reactive } from 'vue'

/** SQL 工作台 Tab 的保留 key（不可关闭）。 */
export const SQL_TAB_KEY = 'sql'

export const tableTabs = reactive({
    /** 已打开的表 Tab 列表（不含固定的 SQL 工作台）。 */
    tabs: [],
    /** 当前激活的 key：SQL_TAB_KEY 或表 Tab 的 key。 */
    activeKey: SQL_TAB_KEY
})

/** 当前激活的表 Tab；激活的是 SQL 工作台时返回 null。 */
export const activeTable = computed(
    () => tableTabs.tabs.find(tab => tab.key === tableTabs.activeKey) || null
)

/** 是否已有打开的表 Tab（决定顶部 Tab 条是否出现）。 */
export const hasTableTabs = computed(() => tableTabs.tabs.length > 0)

function makeKey(env, connectionId, table) {
    return `${env}:${connectionId}:${table}`
}

function findTab(key) {
    return tableTabs.tabs.find(tab => tab.key === key) || null
}

/**
 * 打开一张表：已打开则只激活（不重建，保留分页与数据）；否则新建 Tab。
 *
 * @returns 对应的 Tab 对象（响应式）
 */
export function openTable({env = 'local', connectionId, connectionName = '', dbType = '', table, comment = ''}) {
    const key = makeKey(env, connectionId, table)
    const existing = findTab(key)
    if (existing) {
        tableTabs.activeKey = key
        return existing
    }
    const tab = reactive({
        key,
        env,
        connectionId,
        connectionName,
        dbType,
        table,
        comment,
        view: 'data',
        page: 1,
        pageSize: 50,
        total: 0,
        orderBy: null,
        keyword: '',
        columns: [],
        rows: [],
        sql: '',
        primaryKeys: [],
        editable: false,
        unstable: false,
        writeEnabled: false,
        structure: null,
        structureAt: 0,
        /** 后端缓存这份结构的抓取时间（展示「结构 mm-dd hh:mm」） */
        structureCachedAt: 0,
        dataAt: 0,
        /** 刷新令牌：外部（右键刷新表结构）递增，TableWorkspace 监听它重拉结构与数据 */
        refreshToken: 0,
        /** 编辑请求：外部（右键「编辑表结构」）递增，结构面板挂载后自动进入编辑态 */
        editRequest: 0,
        /** 已被结构面板消费的 editRequest，避免重挂载时重复进入编辑 */
        editHandled: 0,
        loading: false,
        structureLoading: false,
        error: '',
        dirty: 0,
        /* ---- 未提交的行改动（P2） ---- */
        edits: {},
        drafts: [],
        deletes: [],
        /** 本地写开关（来自 /api/db/write-status），关闭时网格只读 */
        writeEnabled: false,
        /** 线上写开关（env=online 时生效） */
        onlineWrite: false
    })
    tableTabs.tabs.push(tab)
    tableTabs.activeKey = key
    return tab
}

/** 切到指定 Tab（SQL_TAB_KEY 表示回到编辑器）。 */
export function activateTab(key) {
    tableTabs.activeKey = key
}

/** 关闭表 Tab；关的是当前 Tab 时自动落到右邻 / 左邻，都没有则回到 SQL 工作台。 */
export function closeTab(key) {
    const index = tableTabs.tabs.findIndex(tab => tab.key === key)
    if (index < 0) {
        return
    }
    tableTabs.tabs.splice(index, 1)
    if (tableTabs.activeKey === key) {
        const next = tableTabs.tabs[index] || tableTabs.tabs[index - 1]
        tableTabs.activeKey = next ? next.key : SQL_TAB_KEY
    }
}

/** 局部更新某个 Tab 的字段。 */
export function patchTab(key, patch) {
    const tab = findTab(key)
    if (tab) {
        Object.assign(tab, patch)
    }
    return tab
}

/** 结构改动后清掉本地缓存，下次进入表视图会重拉结构与数据。 */
export function invalidateTab(key) {
    const tab = findTab(key)
    if (tab) {
        tab.structure = null
        tab.structureAt = 0
        tab.dataAt = 0
        tab.rows = []
        tab.refreshToken = (tab.refreshToken || 0) + 1
        clearChanges(tab)
    }
    return tab
}

/** 清空未提交改动（提交成功 / 用户撤销时调用）。 */
export function clearChanges(tab) {
    if (!tab) {
        return tab
    }
    tab.edits = {}
    tab.drafts = []
    tab.deletes = []
    return tab
}

/** 当前 Tab 是否有未提交改动（关闭前确认用）。 */
export function hasPendingChanges(tab) {
    if (!tab) {
        return false
    }
    const hasEdits = Object.keys(tab.edits || {}).some(key => Object.keys(tab.edits[key] || {}).length > 0)
    return hasEdits || (tab.drafts || []).length > 0 || (tab.deletes || []).length > 0
}
