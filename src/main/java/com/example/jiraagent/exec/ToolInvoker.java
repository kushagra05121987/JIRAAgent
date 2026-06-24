package com.example.jiraagent.exec;

import com.example.jiraagent.model.PlanStep;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolInvoker {

    private final ToolRegistry registry;
    private final ObjectMapper mapper;

    public ToolInvoker(ToolRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    public StepResult invoke(PlanStep step, Object prev, Object item) {
        Map<String, Object> resolved = resolveArgs(step.args(), prev, item);
        return invokeResolved(step, resolved);
    }

    public StepResult invokeResolved(PlanStep step, Map<String, Object> resolvedArgs) {
        ToolCallback tool = registry.find(step.tool()).orElse(null);
        if (tool == null) {
            return StepResult.fail(step.stepNumber(), step.tool(), resolvedArgs,
                    "No tool registered with name '" + step.tool() + "'.");
        }
        try {
            String json = mapper.writeValueAsString(resolvedArgs);
            String outJson = tool.call(json);
            Object output = parse(outJson);
            return StepResult.ok(step.stepNumber(), step.tool(), resolvedArgs, output);
        } catch (Exception e) {
            return StepResult.fail(step.stepNumber(), step.tool(), resolvedArgs, e.getMessage());
        }
    }

    public Map<String, Object> resolveArgs(Map<String, Object> args, Object prev, Object item) {
        Map<String, Object> out = new HashMap<>();
        if (args == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : args.entrySet()) {
            out.put(e.getKey(), resolveValue(e.getValue(), prev, item));
        }
        return out;
    }

    private Object resolveValue(Object value, Object prev, Object item) {
        if (!(value instanceof String s) || !s.startsWith("$")) {
            return value;
        }
        if (s.equals("$item")) {
            return item;
        }
        if (s.startsWith("$item.")) {
            return field(item, s.substring("$item.".length()));
        }
        if (s.startsWith("$prev.")) {
            return field(prev, s.substring("$prev.".length()));
        }
        if (s.equals("$prev")) {
            return prev;
        }
        return value;
    }

    private Object field(Object obj, String path) {
        Object current = obj;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> m) {
                current = m.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    public Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, new TypeReference<Object>() {});
        } catch (Exception e) {
            return json;
        }
    }

    @SuppressWarnings("unchecked")
    public List<Object> asItemList(Object output) {
        if (output instanceof List<?> list) {
            return (List<Object>) list;
        }
        if (output instanceof Map<?, ?> m && m.get("items") instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }
}