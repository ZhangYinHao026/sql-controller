package com.sxwh.sqlcontroller.controller;

import com.sxwh.sqlcontroller.service.StructureEditService;
import com.sxwh.sqlcontroller.util.WebUtil;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 结构编辑接口：DDL 预览 / 执行。
 *
 * <p>与 {@code /api/db/rows/*} 共用 {@code sql-controller.write.enabled} 开关；
 * 关闭时返回 403，前端结构页不出现「编辑结构」入口。
 */
@RestController
@RequestMapping("/api/db/structure")
@CrossOrigin
public class StructureController {

    private final StructureEditService service;

    public StructureController(StructureEditService service) {
        this.service = service;
    }

    /** 只生成 DDL（前端实时预览用）。 */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Map<String, Object> body) {
        return service.preview(body);
    }

    /** 执行 DDL：必须 confirmed=true；逐条执行，遇错即停。 */
    @PostMapping("/execute")
    public Map<String, Object> execute(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return service.execute(body, WebUtil.clientIp(request));
    }
}
