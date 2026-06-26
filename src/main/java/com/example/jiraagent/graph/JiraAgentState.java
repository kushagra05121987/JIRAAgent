package com.example.jiraagent.graph;

import com.example.jiraagent.exec.StepResult;
import com.example.jiraagent.model.PlanStep;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JiraAgentState extends AgentState {

    public static final String PAGE_STEP = "page_step";
    public static final String BODY_STEPS = "body_steps";
    public static final String OFFSET = "offset";
    public static final String PAGE_SIZE = "page_size";
    public static final String CURRENT_ITEMS = "current_items";
    public static final String TOTAL_COUNT = "total_count";
    public static final String RESULTS = "results";
    public static final String PROCESS_ITEMS_RETRY = "process_items_retry";
    public static final String PROCESS_ITEM_ATTEMPTS = "process_item_attempts";
    public static final String FAILED_STEPS = "failed_steps";

    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            RESULTS, Channels.appender(ArrayList::new)
    );

    public JiraAgentState(Map<String, Object> initData) {
        super(initData);
    }

    public PlanStep pageStep() {
        return this.<PlanStep>value(PAGE_STEP).orElse(null);
    }

    public List<PlanStep> bodySteps() {
        return this.<List<PlanStep>>value(BODY_STEPS).orElse(List.of());
    }

    public int offset() {
        return this.<Integer>value(OFFSET).orElse(0);
    }

    public int pageSize() {
        return this.<Integer>value(PAGE_SIZE).orElse(50);
    }

    public List<Object> currentItems() {
        return this.<List<Object>>value(CURRENT_ITEMS).orElse(List.of());
    }

    public int totalCount() {
        return this.<Integer>value(TOTAL_COUNT).orElse(1);
    }

    public List<StepResult> results() {
        return this.<List<StepResult>>value(RESULTS).orElse(List.of());
    }

    public boolean processItemsRetry() {
        return this.<Boolean>value((PROCESS_ITEMS_RETRY)).orElse(false);
    }

    public int processItemAttempts() {
        return this.<Integer>value(PROCESS_ITEM_ATTEMPTS).orElse(0);
    }

    public List<Object[]> failedSteps() {
        return this.<List<Object[]>>value(FAILED_STEPS).orElse(List.of());
    }
}