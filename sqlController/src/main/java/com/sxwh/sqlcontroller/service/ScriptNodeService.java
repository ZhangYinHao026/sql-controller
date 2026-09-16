package com.sxwh.sqlcontroller.service;

import com.sxwh.sqlcontroller.model.ScriptNode;
import com.sxwh.sqlcontroller.repository.ScriptNodeRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 脚本树的节点校验、移动和级联删除服务。
 */
@Service
public class ScriptNodeService {
    private static final long ROOT_NODE_ID = 1L;
    private final ScriptNodeRepository repository;

    public ScriptNodeService(ScriptNodeRepository repository) {
        this.repository = repository;
    }

    public List<ScriptNode> list() {
        return repository.findAll();
    }

    public ScriptNode get(Long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("脚本节点不存在"));
    }

    public ScriptNode create(ScriptNode node) {
        normalizeAndValidate(node, null);
        return saveWithUniqueName(node, true);
    }

    public ScriptNode update(Long id, ScriptNode node) {
        ScriptNode existing = get(id);
        node.setId(id);
        if (ROOT_NODE_ID == id && (!"folder".equals(node.getNodeType()) || node.getParentId() != null)) {
            throw new IllegalArgumentException("默认根目录不能修改类型或移动");
        }
        normalizeAndValidate(node, id);
        return saveWithUniqueName(node, false);
    }

    public void updateOpen(Long id, boolean open) {
        ScriptNode node = get(id);
        if (!"folder".equals(node.getNodeType())) throw new IllegalArgumentException("只有文件夹可以设置展开状态");
        repository.updateOpen(id, open);
    }

    public void move(Long id, Long parentId, Integer sortOrder) {
        if (ROOT_NODE_ID == id) throw new IllegalArgumentException("默认根目录不能移动");
        ScriptNode node = get(id);
        validateParent(node.getNodeType(), parentId, id);
        if (repository.existsSiblingName(parentId, node.getName(), id))
            throw new IllegalArgumentException("目标目录下已存在同名节点");
        repository.updateMove(id, parentId, sortOrder == null ? node.getSortOrder() : sortOrder);
    }

    public void delete(Long id) {
        if (id == null || ROOT_NODE_ID == id) throw new IllegalArgumentException("默认根目录不能删除");
        get(id);
        repository.delete(id);
    }

    private void normalizeAndValidate(ScriptNode node, Long editingId) {
        if (node == null || !("folder".equals(node.getNodeType()) || "file".equals(node.getNodeType()))) {
            throw new IllegalArgumentException("节点类型必须是 folder 或 file");
        }
        if (node.getName() == null || node.getName().trim().isEmpty())
            throw new IllegalArgumentException("节点名称不能为空");
        node.setName(node.getName().trim());
        if (node.getSortOrder() == null) node.setSortOrder(0);
        if (node.getIsOpen() == null) node.setIsOpen(true);
        if ("folder".equals(node.getNodeType())) node.setSqlText(null);
        if ("file".equals(node.getNodeType()) && (node.getSqlText() == null || node.getSqlText().trim().isEmpty())) {
            throw new IllegalArgumentException("脚本 SQL 不能为空");
        }
        validateParent(node.getNodeType(), node.getParentId(), editingId);
        if (repository.existsSiblingName(node.getParentId(), node.getName(), editingId))
            throw new IllegalArgumentException("同一目录下节点名称不能重复");
    }

    private ScriptNode saveWithUniqueName(ScriptNode node, boolean creating) {
        try {
            return creating ? repository.insert(node) : repository.update(node);
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("同一目录下节点名称不能重复");
        }
    }

    private void validateParent(String nodeType, Long parentId, Long selfId) {
        if (parentId == null) return;
        if (parentId.equals(selfId)) throw new IllegalArgumentException("节点不能移动到自身");
        ScriptNode parent = get(parentId);
        if (!"folder".equals(parent.getNodeType())) throw new IllegalArgumentException("父节点必须是文件夹");
        Long cursor = parent.getParentId();
        while (cursor != null) {
            if (cursor.equals(selfId)) throw new IllegalArgumentException("节点不能移动到自身的子孙目录");
            cursor = get(cursor).getParentId();
        }
    }
}
