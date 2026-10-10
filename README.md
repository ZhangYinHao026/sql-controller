# SQL 控制台（sql-controller）

面向多数据库的统一 SQL 控制台：在一个页面里管理多个库连接、编写并执行 SQL、把 MySQL 语法转换成国产库方言、比对表结构差异并一键生成补丁脚本。

支持数据库：**MySQL**、**达梦 DM8**、**神通 Oscar**（openGauss 内核）。

## 功能特性

**连接管理**
- 多库连接新增 / 编辑 / 测试 / 启停，按数据库类型自动带出默认端口与 JDBC 模板
- 连接按类型分组展示，支持排序与节点树

**SQL 执行**
- 多连接并发执行，结果分标签展示，支持分页、复制、导出
- 按语句类型分层超时：查询 300s / DML 300s / DDL 600s，长 DDL 不会被网关提前掐断

**SQL 转换**
- MySQL 语法 → 达梦 / 神通方言（基于 Druid AST 重写，非正则替换）
- 「一键转换并执行」：按各连接 dbType 分组下发，MySQL 直发、达梦/神通先转换
- 转换结果面板逐句展示方言脚本与告警（不支持的语句类型会明确提示并跳过）

**字段结构对比**
- 选 1 个基准库 + N 个比对库，拉取快照后逐表比对：列、类型、长度精度、约束、默认值、自增、主键、索引、表注释
- 索引跨方言按「唯一性 + 列序列」签名对齐，不比较索引名（达梦/神通常用系统生成名）
- 左栏徽章为**跨库总差异**；单表核对的「比对库」pills 跟随选中表显示**各库自身差异**（缺N / 多N / 核N / 缺表 / ✓）
- 一键生成补丁并按目标库分桶：目标为达梦/神通时默认展示已转换的「目标方言」脚本，也可切回源脚本编辑
- 补丁按目标库类型整备（目标 MySQL 时把达梦/神通类型名转成等价的 MySQL 类型）
- 缺整表可一键生成 `CREATE TABLE`，索引与表注释一并还原

**编辑器与脚本编排**
- CodeMirror 6：SQL 高亮、表名 / 列名 / 关键字联想（关键字白名单按方言收敛，减少噪音）
- 命令节点树 + 参数化脚本（`${param}`）管理与执行
- 执行历史留存与复用

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + Vite 6 + CodeMirror 6（无 UI 框架，手写样式） |
| 后端 | Spring Boot 2.6.13 / Java 8 / Maven |
| 数据访问 | JdbcTemplate + Druid 连接池 + 动态数据源（按连接切换） |
| SQL 解析 | Alibaba Druid 1.2.20 |
| 元数据存储 | MySQL（保存连接、脚本节点、执行历史） |
| 驱动 | mysql-connector-java / dm-jdbc-driver（达梦）/ oscar-jdbc（神通） |

## 目录结构

```
sql-controller-vue/          前端（Vue3 + Vite）
  src/components/            页面与弹窗组件（编辑器、连接、转换、字段对比、补丁…）
  src/utils/                 纯逻辑工具（schemaDiff 差异引擎、sqlStatements、dbRegistry、sqlWords…）
  tests/                     Node 内置测试（node --test）
sqlController/               后端（Spring Boot）
  src/main/java/.../controller/   REST 接口（database / sql / diff / autocomplete / nodes / history）
  src/main/java/.../service/      连接管理、动态数据源、结构快照、方言转换、脚本编排
  src/main/resources/application.yml
deploy/                      本地部署脚本（不入库）
```

## 接口一览（前缀 `/api`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET / POST | `/database/connections` | 连接列表 / 新建 |
| PUT / DELETE | `/database/connections/{id}` | 更新 / 删除连接 |
| POST | `/database/connections/{id}/test`、`/database/connections/test-draft` | 连接测试（已存连接 / 未保存草稿） |
| POST | `/sql/execute`、`/sql/cancel` | 执行 SQL / 取消执行 |
| POST | `/sql/convert` | SQL 方言转换（MySQL → 达梦 / 神通） |
| GET | `/diff/types` | 差异类型元信息 |
| POST | `/diff/schema-snapshot` | 结构快照（支持 `tables` 局部刷新与 `force` 强制重抓） |
| GET | `/autocomplete/schema` | 表 / 列联想数据源 |
| GET / POST / PUT / DELETE | `/nodes` | 命令节点树增删改查 |
| GET | `/nodes/{id}/parameters` | 节点脚本参数列表 |
| GET | `/sql/history` | 执行历史 |

## 本地运行

前置：JDK 8+、Maven 3.8+、Node 22.x。

**后端**（默认 8083；需要一个 MySQL 存元数据，建库脚本见 `sqlController/src/main/resources/schema.sql`）

```bash
cd sqlController && mvn spring-boot:run
```

**前端**（dev 默认 5173，`/api` 已反代到 `http://localhost:8083`）

```bash
cd sql-controller-vue && npm install && npm run dev
```

## 测试

```bash
cd sql-controller-vue && node --test tests/*.test.js   # 前端：差异引擎 / 工具函数 / 组件结构回归
cd sqlController && mvn test                           # 后端
```

## 构建与部署

```bash
cd sql-controller-vue && npm run build                 # 前端产物 dist/（静态托管）
cd sqlController && mvn -DskipTests package            # 后端产物 target/sql-controller-SNAPSHOT.jar
```

部署形态：前端容器内 nginx 托管 `dist/` 并反代 `/api/` 到后端容器；内网部署脚本 `deploy/build-push.sh [all|backend|frontend]`（需配置 SSH 免密，脚本负责传产物并 reload nginx）。

## 已知限制

- SQL 转换只处理 **DDL / DML**，`SELECT` 等查询语句会被跳过并给出 warning（跨库查询需人工改写）。
- 转换为 **MySQL → 达梦 / 神通 单向**；反向（达梦 → MySQL）由「字段对比」在生成阶段按目标库类型整备解决。
- 结构元数据缓存默认 24h（联想与字段对比快照共用），外部改表后需「强制重抓」或按表局部刷新。

## 说明

生产部署时请用环境变量或外部配置文件覆盖 `application.yml` 中的连接信息，不要把任何口令提交到仓库。
