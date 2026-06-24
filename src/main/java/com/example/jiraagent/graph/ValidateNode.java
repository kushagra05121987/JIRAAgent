package com.example.jiraagent.graph;

import com.example.jiraagent.guardrail.GuardrailResult;
import com.example.jiraagent.guardrail.OutputGuardrail;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class ValidateNode implements NodeAction<PlanGraphState> {

    private static final Logger log = LoggerFactory.getLogger(ValidateNode.class);

    private final OutputGuardrail guardrail;

    public ValidateNode(OutputGuardrail guardrail) {
        this.guardrail = guardrail;
    }

    @Override
    public Map<String, Object> apply(PlanGraphState state) {
        GuardrailResult result = guardrail.check(state.plan());

        log.debug("validate node: allowed={} reason={}", result.allowed(), result.reason());

        Map<String, Object> update = new HashMap<>();
        update.put(PlanGraphState.VALID, result.allowed());
        update.put(PlanGraphState.REASON, result.reason());
        update.put(PlanGraphState.WARNINGS, result.warnings());
        return update;
    }
}