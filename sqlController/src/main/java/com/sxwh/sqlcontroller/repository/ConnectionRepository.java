package com.sxwh.sqlcontroller.repository;

import com.sxwh.sqlcontroller.model.DatabaseConnection;

import java.util.*;

public interface ConnectionRepository {
    List<DatabaseConnection> find(String type, String keyword);

    Optional<DatabaseConnection> findById(Long id);

    DatabaseConnection save(DatabaseConnection c);

    void delete(Long id);

    List<Map<String, Object>> typeCounts();
}
