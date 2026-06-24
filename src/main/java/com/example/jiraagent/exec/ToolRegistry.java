package com.example.jiraagent.exec;

import com.example.jiraagent.tools.ToolProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ToolRegistry {

    private final Map<String, ToolCallback> byName = new LinkedHashMap<>();

    public ToolRegistry(List<ToolProvider> toolBeans) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(toolBeans.toArray())
                .build()
                .getToolCallbacks();

        for (ToolCallback cb : callbacks) {
            byName.put(cb.getToolDefinition().name(), cb);
        }
    }

    public Optional<ToolCallback> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name));
    }

    public boolean has(String name) {
        return name != null && byName.containsKey(name);
    }

    public List<ToolDefinition> definitions() {
        return byName.values().stream().map(ToolCallback::getToolDefinition).toList();
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }
}