import {reactive, ref} from 'vue'

/**
 * 字段对比的会话级状态（模块级单例）。
 *
 * SchemaDiffDialog 是 v-if 挂载的，组件销毁即丢状态 —— 以前关一次弹窗，
 * 拉了几十秒的线上快照就全没了，重开还得重新点「▶ 拉取快照」。
 * 把「费时间攒出来的东西」挪到这里，关闭/重开弹窗原样保留：
 *   - libs          各库结构快照（含 fetchedAt / 列 / 索引 / 注释索引）
 *   - diffBase      基准库
 *   - diffCmps      勾选的比对库（有序）
 *   - diffCmp       单表核对当前激活的比对库
 *   - diffState     单表核对里每列的处理选择（add/drop/mod/skip/back）
 *   - applied       「✓ 已应用」标记
 *   - patchBuckets / patchOrder  补丁分桶（含已编辑但未执行的脚本）
 * 页面刷新（F5）后清空 —— 后端快照缓存（autocomplete.schema-cache-seconds）仍在，
 * 重新点一次「拉取快照」命中缓存，不用再等小助手。
 *
 * 弹窗本地的 UI 状态（搜索词、筛选、面板开合、正在拉取）不进这里，每次打开都是干净的。
 */

export const libs = reactive({})            // connId → {id,name,dbType,fetchedAt,error,tables,tU,cI,ix,cmt}
export const diffBase = ref(null)           // 基准连接 id
export const diffCmps = ref([])             // 勾选的比对库 id（有序）
export const diffCmp = ref(null)            // 单表核对激活的比对库 id
export const diffState = reactive({})       // `${cmp}|${table}|${field}` -> 'add'|'drop'|'mod'|'skip'|'back'
export const applied = reactive({})         // `${cmp}|${table}` -> true
/* ---- 补丁：按目标库分桶（每个比对库一桶 + 基准库一桶），生成只累积、可攒批统一执行 ----
 *   key = String(目标库 connectionId)
 *   status: idle(待执行) / running(执行中) / done(成功·已清空文本) / fail(失败·可重试)
 *   text = 源脚本（MySQL 风格，可编辑，执行时经转换器按目标库方言翻译）
 *   dialectText/dialectType/dialectWarn/dialectErr = 目标方言视图（非 MySQL 时后端转换器产出） */
export const patchBuckets = reactive({})    // key -> {id, text, edited, status, info, dialect*}
export const patchOrder = ref([])           // 有序 key（列表顺序 = 创建顺序）

/** 清空全部会话状态（「↺ 撤销已应用」复位整个对比会话时用）。 */
export function resetDiffSession() {
    for (const k of Object.keys(libs)) delete libs[k]
    for (const k of Object.keys(diffState)) delete diffState[k]
    for (const k of Object.keys(applied)) delete applied[k]
    for (const k of Object.keys(patchBuckets)) delete patchBuckets[k]
    patchOrder.value = []
    diffBase.value = null
    diffCmps.value = []
    diffCmp.value = null
}

/**
 * 连接清单变化后剔除失效条目（连接被删除 / 线上区域下线）。
 * @param {Set} validIds 仍有效的连接 id 集合（本地数字 id + 'online:N' 字符串 id）
 */
export function pruneDiffSession(validIds) {
    // 快照 key 统一是字符串（'1' / 'online:3'），而连接 id 本地是数字 —— 一律按字符串比较，
    // 否则 set.has(1) 对 '1' 为 false，会把所有快照误删。
    const ids = new Set([...validIds].map(String))
    const alive = id => ids.has(String(id))
    for (const k of Object.keys(libs)) if (!alive(k)) delete libs[k]
    for (const k of Object.keys(diffState)) if (!alive(String(k).split('|')[0])) delete diffState[k]
    for (const k of Object.keys(applied)) if (!alive(String(k).split('|')[0])) delete applied[k]
    for (const k of Object.keys(patchBuckets)) if (!alive(k)) delete patchBuckets[k]
    patchOrder.value = patchOrder.value.filter(alive)
    diffCmps.value = diffCmps.value.filter(alive)
    if (diffBase.value != null && !alive(diffBase.value)) diffBase.value = null
    if (diffCmp.value != null && !alive(diffCmp.value)) diffCmp.value = null
}
