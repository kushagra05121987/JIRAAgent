package com.example.jiraagent.graph;

import com.example.jiraagent.model.Plan;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;

import java.util.List;
import java.util.Map;

public class PlanGraphState extends AgentState {

    public static final String PROMPT   = "prompt";
    public static final String PLAN     = "plan";
    public static final String ATTEMPTS = "attempts";
    public static final String VALID    = "valid";
    public static final String REASON   = "reason";
    public static final String WARNINGS = "warnings";

    public static final Map<String, Channel<?>> SCHEMA = Map.of();

    public PlanGraphState(Map<String, Object> initData) {
        super(initData);
    }

    public String prompt() {
        return this.<String>value(PROMPT).orElse("");
    }

    public Plan plan() {
        return this.<Plan>value(PLAN).orElse(null);
    }

    public int attempts() {
        return this.<Integer>value(ATTEMPTS).orElse(0);
    }

    public boolean valid() {
        return this.<Boolean>value(VALID).orElse(false);
    }

    public String reason() {
        return this.<String>value(REASON).orElse(null);
    }

    public List<String> warnings() {
        return this.<List<String>>value(WARNINGS).orElse(List.of());
    }
}