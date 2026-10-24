CREATE TABLE IF NOT EXISTS database_connection (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  db_type VARCHAR(20) NOT NULL COMMENT '数据库类型：mysql、dm、oscar',
  region_name VARCHAR(100) NOT NULL COMMENT '省份或环境名称',
  host VARCHAR(255) NOT NULL COMMENT '数据库主机地址',
  port INT NOT NULL COMMENT '数据库端口',
  database_name VARCHAR(255) COMMENT '数据库名称',
  jdbc_url VARCHAR(1000) NOT NULL COMMENT 'JDBC 连接地址',
  username VARCHAR(200) NOT NULL COMMENT '数据库用户名',
  password_encrypted TEXT NOT NULL COMMENT '数据库连接密码',
  driver_class VARCHAR(200) NOT NULL COMMENT 'JDBC 驱动类',
  remark VARCHAR(500) COMMENT '备注',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_db_region (db_type, region_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据库连接配置表';

CREATE TABLE IF NOT EXISTS sql_execution_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(64) NOT NULL COMMENT '请求追踪 ID',
  connection_id BIGINT NULL COMMENT '数据库连接 ID（线上写入为空）',
  script_node_id BIGINT NULL COMMENT '关联脚本节点 ID',
  script_name VARCHAR(200) NULL COMMENT '执行脚本名称',
  db_type VARCHAR(20) NOT NULL COMMENT '数据库类型',
  region_name VARCHAR(100) NOT NULL COMMENT '省份或环境名称',
  sql_text TEXT NOT NULL COMMENT '执行的 SQL 文本',
  sql_type VARCHAR(30) NOT NULL COMMENT 'SQL 类型',
  execution_mode VARCHAR(20) NOT NULL COMMENT '执行模式',
  status VARCHAR(20) NOT NULL COMMENT '执行状态',
  affected_rows BIGINT DEFAULT 0 COMMENT '影响行数',
  client_ip VARCHAR(64) NULL COMMENT '操作人 IP（X-Forwarded-For 首段）',
  duration_ms BIGINT DEFAULT 0 COMMENT '执行耗时，单位毫秒',
  error_message TEXT COMMENT '错误信息',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  KEY idx_history_created (created_at),
  KEY idx_history_filter (db_type, region_name, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL 执行历史记录表';

CREATE TABLE IF NOT EXISTS script_node (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '脚本树节点主键',
  node_type VARCHAR(10) NOT NULL COMMENT '节点类型：folder 或 file',
  name VARCHAR(200) NOT NULL COMMENT '文件夹名称或脚本名称',
  parent_id BIGINT NULL COMMENT '父节点 ID，NULL 表示根节点',
  sql_text TEXT NULL COMMENT 'SQL 脚本内容，文件夹为空',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '同级节点排序值',
  is_open TINYINT(1) NOT NULL DEFAULT 1 COMMENT '文件夹是否展开',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  CONSTRAINT chk_script_node_type CHECK (node_type IN ('folder','file')),
  CONSTRAINT fk_script_node_parent FOREIGN KEY (parent_id) REFERENCES script_node(id) ON DELETE CASCADE,
  UNIQUE KEY uk_script_node_sibling (parent_id, name),
  KEY idx_script_node_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL 脚本和文件夹树节点表';

INSERT IGNORE INTO script_node(id, node_type, name, parent_id, sql_text, sort_order, is_open)
VALUES (1, 'folder', '默认分类', NULL, NULL, 0, 1);

-- 线上环境：每个区域一条。查询统一经远程查询小助手（area 参数区分区域），
-- 修改走该省 sxwh-allowance 的 updateSql（write_base_url，空=仅可查）。
-- 与 database_connection 完全独立：无 host/port/账号，ID 空间独立（前端用 online:<id> 区分）。
CREATE TABLE IF NOT EXISTS online_connection (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  area_code INT NOT NULL COMMENT '小助手区域码，如 32=江苏省',
  region_name VARCHAR(100) NOT NULL COMMENT '区域名，如 江苏省',
  db_type VARCHAR(20) NOT NULL DEFAULT 'mysql' COMMENT '该区域库类型：mysql/dm/oscar（字段对比同步时决定方言转换目标）',
  display_name VARCHAR(100) COMMENT '展示名，空则用 region_name',
  ver VARCHAR(10) NOT NULL DEFAULT 'BT21' COMMENT '小助手版本：BT18/BT21/BF',
  write_base_url VARCHAR(500) COMMENT '该省 allowance 部署地址；空=仅可查不可写',
  enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  remark VARCHAR(500) COMMENT '备注',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_online_ver_area (ver, area_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='线上环境配置表（每区域一条）';
