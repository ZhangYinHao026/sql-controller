package com.sxwh.sqlcontroller.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    /** 带状态码的业务拒绝（如写入口未开启 403）：保留原状态码，不降级成 500。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("message", e.getReason() == null ? e.getMessage() : e.getReason());
        return ResponseEntity.status(e.getStatus()).body(b);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> bad(RuntimeException e) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(b);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> error(Exception e) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("message", "服务器处理失败");
        b.put("detail", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(b);
    }
}
