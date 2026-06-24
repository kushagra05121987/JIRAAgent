package com.example.jiraagent.exec;

import com.example.jiraagent.model.Plan;
import com.example.jiraagent.model.PlanStep;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;

@Service
public class PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutor.class);

    private final ToolRegistry registry;
    private final ObjectMapper mapper;
    private final int pageSize;

    public PlanExecutor(ToolRegistry registry, ObjectMapper mapper, @Value("${agent.executor.page-size:50}") int pageSize, @Value("${agent.executor.item-concurrency:8}") int itemConcurrency) {
        this.registry = registry;
        this.mapper = mapper;
        this.pageSize = pageSize;
    }

    public ExecutionReport execute(Plan plan) {
        ConcurrentLinkedQueue<StepResult> results = new ConcurrentLinkedQueue<>();

        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            return new ExecutionReport(false, "Empty plan; nothing to execute.", List.of());
        }

        List<PlanStep> steps = plan.steps();
        int pageIdx = indexOfPaginatedStep(steps);

        try {
            if (pageIdx < 0) {
                Object prev = null;
                for (PlanStep step : steps) {
                    StepResult r = runOnce(step, prev, null, results);
                    if (!r.success()) {
                        return abort(results, r);
                    }
                    prev = r.output();
                }
            } else {
                runPaginatedParallel(steps, pageIdx, null, results);
            }
        } catch (ExecutionAbort abort) {
            return new ExecutionReport(false, abort.getMessage(), new ArrayList<>(results));
        }

        return new ExecutionReport(true, "Plan executed.", new ArrayList<>(results));
    }

    private void runPaginatedParallel(List<PlanStep> steps, int pageIdx, Object prevForPage, ConcurrentLinkedQueue<StepResult> results) {
        PlanStep pageStep = steps.get(pageIdx);
        List<PlanStep> body = IntStream.range(0, steps.size()).filter(i -> i != pageIdx).mapToObj(steps::get).toList();


        int offset = 0;
        while (true) {
            Map<String, Object> pageArgs = new HashMap<>(resolveArgs(pageStep.args(), prevForPage, null));
            pageArgs.put("offset", offset);
            pageArgs.put("limit", pageSize);

            StepResult pageResult = invoke(pageStep, pageArgs, results);
            if (!pageResult.success()) {
                throw new ExecutionAbort("Pagination step failed at offset " + offset + ": " + pageResult.error());
            }

            List<Object> items = asItemList(pageResult.output());
            if (items.isEmpty()) {
                break;
            }

            List<CompletableFuture<Void>> futures = new ArrayList<>(items.size());
            for (Object item : items) {
                CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                    runBodyForItem(body, pageResult.output(), item, results);
                });
                futures.add(f);
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            if (items.size() < pageSize) {
                break;
            }
            offset += pageSize;
        }
    }

    private void runBodyForItem(List<PlanStep> body, Object pageOutput, Object item, ConcurrentLinkedQueue<StepResult> results) {
        for (PlanStep step : body) {
            StepResult r = runOnce(step, pageOutput, item, results);
            if (!r.success()) {
                log.warn("Step {} ({}) failed for item {}: {}", step.stepNumber(), step.tool(), item, r.error());
                return;
            }
        }
    }

    private StepResult runOnce(PlanStep step, Object prev, Object item, ConcurrentLinkedQueue<StepResult> results) {
        Map<String, Object> resolved = resolveArgs(step.args(), prev, item);
        return invoke(step, resolved, results);
    }

    private StepResult invoke(PlanStep step, Map<String, Object> resolvedArgs, ConcurrentLinkedQueue<StepResult> results) {
        ToolCallback tool = registry.find(step.tool()).orElse(null);
        if (tool == null) {
            StepResult r = StepResult.fail(step.stepNumber(), step.tool(), resolvedArgs, "No tool registered with name '" + step.tool() + "'.");
            results.add(r);
            return r;
        }
        try {
            String json = mapper.writeValueAsString(resolvedArgs);
            String outJson = tool.call(json);
            Object output = parse(outJson);
            StepResult r = StepResult.ok(step.stepNumber(), step.tool(), resolvedArgs, output);
            results.add(r);
            return r;
        } catch (Exception e) {
            StepResult r = StepResult.fail(step.stepNumber(), step.tool(), resolvedArgs, e.getMessage());
            results.add(r);
            return r;
        }
    }

    private Map<String, Object> resolveArgs(Map<String, Object> args, Object prev, Object item) {
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

    private Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, new TypeReference<Object>() {
            });
        } catch (Exception e) {
            return json;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> asItemList(Object output) {
        if (output instanceof List<?> list) {
            return (List<Object>) list;
        }
        if (output instanceof Map<?, ?> m && m.get("items") instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    private int indexOfPaginatedStep(List<PlanStep> steps) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).paginated()) {
                return i;
            }
        }
        return -1;
    }

    private ExecutionReport abort(ConcurrentLinkedQueue<StepResult> results, StepResult failed) {
        return new ExecutionReport(false, "Aborted at step " + failed.stepNumber() + " (" + failed.tool() + "): " + failed.error(), new ArrayList<>(results));
    }

    private static final class ExecutionAbort extends RuntimeException {
        ExecutionAbort(String message) {
            super(message);
        }
    }
}