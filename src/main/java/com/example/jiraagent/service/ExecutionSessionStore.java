package com.example.jiraagent.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ExecutionSessionStore {

    private final ConcurrentHashMap<String, PendingExecution> sessions = new ConcurrentHashMap<>();

    public void put(String threadId, PendingExecution execution) {
        sessions.put(threadId, execution);
    }

    public Optional<PendingExecution> get(String threadId) {
        return Optional.ofNullable(sessions.get(threadId));
    }

    public void remove(String threadId) {
        sessions.remove(threadId);
    }
}
