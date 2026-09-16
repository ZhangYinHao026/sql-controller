/**
 * 前端统一数据库类型注册表（对齐后端 DialectRegistry / JdbcPlatformRegistry 的收集式架构）
 *
 * 新增数据库类型时：只需在本文件 DB_REGISTRY 追加一行（+ 需要进 SQL 转换时在 CONVERT_TARGETS 追加 key），
 * 页面以下位置自动跟随，无需逐处手改：
 *   - 顶栏「数据库类型」chips（App.vue typeNames 派生自本表）
 *   - 新建/编辑连接表单：类型下拉、默认端口、JDBC 预览（ConnectionFormDialog）
 *   - SQL 转换结果槽（ConvertDialog 目标列表）
 *   - 字段对比的类型显示名（SchemaDiffDialog）
 *   - 设置页连接列表「类型」列（ConnectionSettings）
 *
 * label：全站统一显示名（与历史各页既有文案一致：MySQL / DM8 / Oscar）
 * port ：新建连接时该类型的默认端口
 * jdbc ：JDBC URL 预览/提交用模板（host/port/database 由表单提供；未知类型返回 null，前端显式提示而非拼错）
 */
export const DB_REGISTRY = {
  mysql: {
    label: 'MySQL',
    hint: '源',
    // UI 品牌色（SQL 转换 chips / 结果面板 / 页签共用）：dot=色点 / bg=底色 / ink=强调文字
    dot: '#3FB58F', bg: '#E3F6EF', ink: '#1F7A5F',
    port: 3306,
    jdbc: ({ host, port, database }) =>
      `jdbc:mysql://${host}:${port}/${database}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai`
  },
  dm: {
    label: 'DM8',
    hint: '达梦',
    dot: '#3FB58F', bg: '#E3F6EF', ink: '#1F7A5F',
    port: 15236,
    jdbc: ({ host, port, database }) =>
      `jdbc:dm://${host}:${port}/${database}?serverTimezone=UTC&useSSL=false&useUnicode=true&characterEncoding=utf-8&clobAsString=true&columnNameUpperCase=false`
  },
  oscar: {
    label: 'Oscar',
    hint: '神通',
    dot: '#D4537E', bg: '#FBEAF0', ink: '#993556',
    port: 2003,
    jdbc: ({ host, port, database }) => `jdbc:oscar://${host}:${port}/${database}`
  }
}

/** SQL 转换目标（顺序即 ConvertDialog chips/结果槽顺序）。需要支持新目标时在此追加 key。 */
export const CONVERT_TARGETS = ['dm', 'oscar']

/** 显示名兜底：未注册类型原样返回 key（不崩、可读），已注册返回统一 label。 */
export const typeLabel = key => DB_REGISTRY[key]?.label || key

/** 未注册类型的 JDBC 预览占位（前端显式提示，杜绝原先 else 兜底拼成错误驱动 URL 的隐患）。 */
export const UNKNOWN_JDBC_PREFIX = '（未注册类型，无法生成 JDBC 地址）'

/** JDBC 构造：未知类型返回 null，调用方应显示友好提示。 */
export function jdbcUrlOf(type, { host, port, database }) {
  const meta = DB_REGISTRY[type]
  if (!meta) return null
  try {
    return meta.jdbc({ host, port, database })
  } catch {
    return null
  }
}

/** 默认端口（新建连接切换类型时自动填入）。 */
export function defaultPortOf(type) {
  return DB_REGISTRY[type]?.port
}
