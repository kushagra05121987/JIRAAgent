package com.example.jiraagent.graph;

import com.example.jiraagent.model.Plan;
import com.example.jiraagent.service.PlannerService;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class PlanNode implements NodeAction<PlanGraphState> {

    private static final Logger log = LoggerFactory.getLogger(PlanNode.class);

    private final PlannerService planner;

    public PlanNode(PlannerService planner) {
        this.planner = planner;
    }

    @Override
    public Map<String, Object> apply(PlanGraphState state) {
        int attempt = state.attempts();
        String priorReason = state.reason(); // null on first attempt

        log.debug("plan node: attempt {} (priorFailure={})", attempt + 1, priorReason);

        Plan plan = planner.plan(state.prompt(), priorReason);

        Map<String, Object> update = new HashMap<>();
        update.put(PlanGraphState.PLAN, plan);
        update.put(PlanGraphState.ATTEMPTS, attempt + 1);
        return update;
    }
}