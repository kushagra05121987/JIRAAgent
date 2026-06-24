package com.example.jiraagent.graph;

import com.example.jiraagent.exec.StepResult;
import com.example.jiraagent.exec.ToolInvoker;
import com.example.jiraagent.model.PlanStep;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FetchPageNode implements NodeAction<JiraAgentState> {

    private static final Logger log = LoggerFactory.getLogger(FetchPageNode.class);

    private final ToolInvoker invoker;

    public FetchPageNode(ToolInvoker invoker) {
        this.invoker = invoker;
    }

    @Override
    public Map<String, Object> apply(JiraAgentState state) {
        PlanStep pageStep = state.pageStep();
        int offset = state.offset();
        int pageSize = state.pageSize();

        Map<String, Object> args = new HashMap<>(invoker.resolveArgs(pageStep.args(), null, null));
        args.put("offset", offset);
        args.put("limit", pageSize);

        StepResult fetch = invoker.invokeResolved(pageStep, args);
        List<Object> items = fetch.success() ? invoker.asItemList(fetch.output()) : List.of();

        log.debug("fetch_page offset={} -> {} items", offset, items.size());

        Map<String, Object> update = new HashMap<>();
        update.put(JiraAgentState.CURRENT_ITEMS, items);
        update.put(JiraAgentState.RESULTS, List.of(fetch));
        return update;
    }
}