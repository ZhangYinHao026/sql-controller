package com.sxwh.sqlcontroller.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestControllerAdvice
public class ApiExceptionHandler {
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
