package com.sxwh.sqlcontroller.model;

import java.util.ArrayList;
import java.util.List;

/** SQL 方言转换结果：转换后的语句列表 + 转换警告。 */
public class ConvertResult {
    /** 是否整体转换成功（有 warning 不视为失败）。 */
    private boolean success;
    /** 目标数据库类型：dm / oscar。 */
    private String targetDbType;
    /** 转换后的目标方言 SQL 语句（一条 MySQL 可能拆成多条）。 */
    private List<String> statements = new ArrayList<>();
    /** 转换提示：改了什么、丢了什么、需要人工核对的地方。 */
    private List<String> warnings = new ArrayList<>();
    /** 失败原因（success=false 时有效）。 */
    private String error;

    public ConvertResult() {
    }

    public ConvertResult(String targetDbType) {
        this.targetDbType = targetDbType;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getTargetDbType() {
        return targetDbType;
    }

    public void setTargetDbType(String targetDbType) {
        this.targetDbType = targetDbType;
    }

    public List<String> getStatements() {
        return statements;
    }

    public void setStatements(List<String> statements) {
        this.statements = statements;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
