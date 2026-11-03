package com.sxwh.sqlcontroller.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class HistoryRepository {
    /**
     * 执行历史的分档规则（与前端「全部 / 新增 / 修改 / 删除」对应）。
     * 历史只记写操作；DDL 按「建 / 改 / 删」分别落到三档里。
     */
    public static final Map<String, List<String>> CATEGORY_TYPES = new LinkedHashMap<>();

    static {
        CATEGORY_TYPES.put("insert", Arrays.asList("INSERT", "REPLACE", "CREATE", "MERGE"));
        CATEGORY_TYPES.put("update", Arrays.asList("UPDATE", "ALTER", "COMMENT", "RENAME", "DDL"));
        CATEGORY_TYPES.put("delete", Arrays.asList("DELETE", "DROP", "TRUNCATE"));
    }

    private final JdbcTemplate jdbc;

    public HistoryRepository(JdbcTemplate j) {
        jdbc = j;
    }

    /** 把 sql_type 归到 insert/update/delete；认不出的返回 null（只在「全部」里出现） */
    public static String category(String sqlType) {
        String t = sqlType == null ? "" : sqlType.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        for (Map.Entry<String, List<String>> e : CATEGORY_TYPES.entrySet()) {
            if (e.getValue().contains(t)) return e.getKey();
        }
        return null;
    }

    /** 分档过滤条件（值来自本类常量，非外部输入，不构成注入面） */
    private static String kindClause(String kind) {
        List<String> types = kind == null ? null : CATEGORY_TYPES.get(kind.trim().toLowerCase(Locale.ROOT));
        if (types == null) return "";
        StringBuilder sb = new StringBuilder(" and sql_type in (");
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('\'').append(types.get(i)).append('\'');
        }
        return sb.append(')').toString();
    }

    /** 保存执行记录（带操作人 IP；线上写入时 connectionId 为空、type='online'）。 */
    public void save(String req, Long cid, Long scriptNodeId, String scriptName, String type, String region, String sql,
                     String kind, String mode, String status, long affected, String clientIp, long duration, String error) {
        jdbc.update("insert into sql_execution_history(request_id,connection_id,script_node_id,script_name,db_type,region_name,sql_text,sql_type,execution_mode,status,affected_rows,client_ip,duration_ms,error_message) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                req, cid, scriptNodeId, scriptName, type, region, sql, kind, mode, status, affected, clientIp, duration, error);
    }

    public List<Map<String, Object>> list(int page, int size, String type, String region, String status, String kind) {
        String q = "select id,request_id as requestId,connection_id as connectionId,script_node_id as scriptNodeId,script_name as scriptName,db_type as dbType,region_name as regionName,sql_text as sqlText,sql_type as sqlType,execution_mode as executionMode,status,affected_rows as affectedRows,client_ip as clientIp,duration_ms as durationMs,error_message as errorMessage,created_at as createdAt from sql_execution_history where (? is null or db_type=?) and (? is null or region_name like ?) and (? is null or status=?)"
                + kindClause(kind) + " order by id desc limit ? offset ?";
        String r = region == null || region.trim().isEmpty() ? null : "%" + region.trim() + "%";
        return jdbc.queryForList(q, type, type, r, r, status, status, size, (page - 1) * size);
    }

    /** 按相同过滤条件统计总条数（供分页） */
    public int count(String type, String region, String status, String kind) {
        String q = "select count(*) from sql_execution_history where (? is null or db_type=?) and (? is null or region_name like ?) and (? is null or status=?)"
                + kindClause(kind);
        String r = region == null || region.trim().isEmpty() ? null : "%" + region.trim() + "%";
        Integer total = jdbc.queryForObject(q, Integer.class, type, type, r, r, status, status);
        return total == null ? 0 : total;
    }

    /** 各档条数（整表统计，不受分页影响）：insert/update/delete/all */
    public Map<String, Object> categoryCounts(String type, String region, String status) {
        String q = "select sql_type, count(*) as c from sql_execution_history where (? is null or db_type=?) and (? is null or region_name like ?) and (? is null or status=?) group by sql_type";
        String r = region == null || region.trim().isEmpty() ? null : "%" + region.trim() + "%";
        List<Map<String, Object>> rows = jdbc.queryForList(q, type, type, r, r, status, status);
        long all = 0, insert = 0, update = 0, delete = 0;
        for (Map<String, Object> row : rows) {
            Object c = row.get("c");
            long n = c instanceof Number ? ((Number) c).longValue() : 0L;
            all += n;
            String cat = category(row.get("sql_type") == null ? null : String.valueOf(row.get("sql_type")));
            if ("insert".equals(cat)) insert += n;
            else if ("update".equals(cat)) update += n;
            else if ("delete".equals(cat)) delete += n;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("all", all);
        out.put("insert", insert);
        out.put("update", update);
        out.put("delete", delete);
        return out;
    }

    public Map<String, Object> get(Long id) {
        return jdbc.queryForMap("select id,request_id as requestId,connection_id as connectionId,script_node_id as scriptNodeId,script_name as scriptName,db_type as dbType,region_name as regionName,sql_text as sqlText,sql_type as sqlType,execution_mode as executionMode,status,affected_rows as affectedRows,client_ip as clientIp,duration_ms as durationMs,error_message as errorMessage,created_at as createdAt from sql_execution_history where id=?", id);
    }
}
