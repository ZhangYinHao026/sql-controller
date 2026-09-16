package com.sxwh.sqlcontroller.controller;

import com.sxwh.sqlcontroller.model.ScriptNode;
import com.sxwh.sqlcontroller.service.ScriptNodeService;
import com.sxwh.sqlcontroller.service.ScriptParameterService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 侧边栏 SQL 脚本树的统一节点接口。
 */
@RestController
@RequestMapping("/api/nodes")
@CrossOrigin
public class ScriptNodeController {
    private final ScriptNodeService service;
    private final ScriptParameterService parameterService;

    public ScriptNodeController(ScriptNodeService service, ScriptParameterService parameterService) {
        this.service = service;
        this.parameterService = parameterService;
    }

    /**
     * 返回扁平节点列表，前端按 parentId 自行组装无限层级树。
     */
    @GetMapping
    public List<ScriptNode> list() {
        return service.list();
    }

    /**
     * 新建文件夹或 SQL 脚本。
     */
    @PostMapping
    public ScriptNode create(@RequestBody ScriptNode node) {
        node.setId(null);
        return service.create(node);
    }

    /**
     * 修改节点名称、SQL 内容、父目录、排序或展开状态。
     */
    @PutMapping("/{id}")
    public ScriptNode update(@PathVariable Long id, @RequestBody ScriptNode node) {
        return service.update(id, node);
    }

    /**
     * 删除节点；文件夹会由数据库外键级联删除其全部子树。
     */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /**
     * 保存文件夹展开或收起状态。
     */
    @PutMapping("/{id}/open")
    public void updateOpen(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object open = body.get("isOpen");
        if (!(open instanceof Boolean)) throw new IllegalArgumentException("isOpen 必须是布尔值");
        service.updateOpen(id, (Boolean) open);
    }

    /**
     * 处理拖拽移动和同级排序。
     */
    @PutMapping("/{id}/move")
    public void move(@PathVariable Long id, @RequestBody ScriptNode node) {
        service.move(id, node.getParentId(), node.getSortOrder());
    }

    /**
     * 解析脚本中的 #{参数名}、${参数名} 占位符，按首次出现顺序返回。
     */
    @GetMapping("/{id}/parameters")
    public Map<String, Object> parameters(@PathVariable Long id) {
        ScriptNode node = service.get(id);
        if (!"file".equals(node.getNodeType())) throw new IllegalArgumentException("只有脚本节点可以解析参数");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parameters", parameterService.parseParameters(node.getSqlText()));
        return result;
    }
}
