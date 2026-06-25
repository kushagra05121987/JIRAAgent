package com.example.jiraagent.graph;

import com.example.jiraagent.guardrail.GuardrailResult;
import com.example.jiraagent.guardrail.OutputGuardrail;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.FluxSink;

import java.util.HashMap;
import java.util.Map;

public class ValidateNode implements NodeAction<PlanGraphState> {

    private static final Logger log = LoggerFactory.getLogger(ValidateNode.class);

    private final OutputGuardrail guardrail;
    private final FluxSink<ServerSentEvent<Object>> sink;

    public ValidateNode(OutputGuardrail guardrail, FluxSink<ServerSentEvent<Object>> sink) {
        this.guardrail = guardrail;
        this.sink = sink;
    }

    @Override
    public Map<String, Object> apply(PlanGraphState state) {
        GuardrailResult result = guardrail.check(state.plan(), state.prompt(), sink);

        log.debug("validate node: allowed={} reason={}", result.allowed(), result.reason());

        Map<String, Object> update = new HashMap<>();
        update.put(PlanGraphState.VALID, result.allowed());
        update.put(PlanGraphState.REASON, result.reason());
        update.put(PlanGraphState.WARNINGS, result.warnings());
        return update;
    }
}