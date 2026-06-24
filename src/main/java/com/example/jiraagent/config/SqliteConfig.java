package com.example.jiraagent.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SqliteConfig {

    private final JdbcTemplate jdbc;

    public SqliteConfig(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void applyPragmas() {
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.execute("PRAGMA busy_timeout=5000");
        jdbc.execute("PRAGMA synchronous=NORMAL");
    }
}