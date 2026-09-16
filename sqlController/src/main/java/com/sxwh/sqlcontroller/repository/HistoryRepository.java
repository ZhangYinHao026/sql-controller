package com.sxwh.sqlcontroller.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class HistoryRepository {
    private final JdbcTemplate jdbc;

    public HistoryRepository(JdbcTemplate j) {
        jdbc = j;
    }

    public void save(String req, Long cid, String type, String region, String sql, String kind, String mode, String status, long affected, long duration, String error) {
        save(req, cid, null, null, type, region, sql, kind, mode, status, affected, duration, error);
    }

    /** 保存执行记录。 */
    public void save(String req, Long cid, Long scriptNodeId, String scriptName, String type, String region, String sql, String kind, String mode, String status, long affected, long duration, String error) {
        jdbc.update("insert into sql_execution_history(request_id,connection_id,script_node_id,script_name,db_type,region_name,sql_text,sql_type,execution_mode,status,affected_rows,duration_ms,error_message) values(?,?,?,?,?,?,?,?,?,?,?,?,?)", req, cid, scriptNodeId, scriptName, type, region, sql, kind, mode, status, affected, duration, error);
    }

    public List<Map<String, Object>> list(int page, int size, String type, String region, String status) {
        String q = "select id,request_id as requestId,connection_id as connectionId,script_node_id as scriptNodeId,script_name as scriptName,db_type as dbType,region_name as regionName,sql_text as sqlText,sql_type as sqlType,execution_mode as executionMode,status,affected_rows as affectedRows,duration_ms as durationMs,error_message as errorMessage,created_at as createdAt from sql_execution_history where (? is null or db_type=?) and (? is null or region_name like ?) and (? is null or status=?) order by id desc limit ? offset ?";
        String r = region == null || region.trim().isEmpty() ? null : "%" + region.trim() + "%";
        return jdbc.queryForList(q, type, type, r, r, status, status, size, (page - 1) * size);
    }

    /** 按相同过滤条件统计总条数（供分页） */
    public int count(String type, String region, String status) {
        String q = "select count(*) from sql_execution_history where (? is null or db_type=?) and (? is null or region_name like ?) and (? is null or status=?)";
        String r = region == null || region.trim().isEmpty() ? null : "%" + region.trim() + "%";
        Integer total = jdbc.queryForObject(q, Integer.class, type, type, r, r, status, status);
        return total == null ? 0 : total;
    }

    public Map<String, Object> get(Long id) {
        return jdbc.queryForMap("select id,request_id as requestId,connection_id as connectionId,script_node_id as scriptNodeId,script_name as scriptName,db_type as dbType,region_name as regionName,sql_text as sqlText,sql_type as sqlType,execution_mode as executionMode,status,affected_rows as affectedRows,duration_ms as durationMs,error_message as errorMessage,created_at as createdAt from sql_execution_history where id=?", id);
    }
}
