package com.example.jiraagent.service;

import com.example.jiraagent.exec.ToolRegistry;
import com.example.jiraagent.model.Plan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

@Service
public class PlannerService {

    private final ChatClient chatClient;
    private final ToolRegistry registry;

    private static final String BASE_INSTRUCTIONS = """
            You are a planning component for a tool-using agent.
            Convert the user's request into an ordered plan of tool-execution requests.
            You never execute anything yourself — you only plan which tools to call and with what arguments.
            The available tools are provided as function definitions. Use ONLY those tools.

            Output rules:
            - Produce a step ONLY for parts of the request that map to an available tool.
            - Number steps from 1, in execution order.
            - A step that retrieves a collection to operate over should set paginated=true.
              The executor will drive it with offset/limit and run the steps AFTER it once
              per returned item. Do NOT put offset or limit in args yourself.
            - If you emit a paginated step, FIRST emit the matching count tool (e.g. COUNT_IDS
              for FETCH_IDS) as a step with isCount=true and the same identifying args (the
              user), without offset/limit. The executor uses this count to know when pagination
              is complete. Only one count step, immediately before the paginated step.
            - Steps after a paginated step operate on a SINGLE item. Each item may be a
              scalar or an object. Reference the current item with "$item", or a field of
              it with "$item.field" (e.g. "$item.issue_key"). Example: a SET_STATUS step
              would use args { "id": "$item.issue_key", "status": "In Progress" }.
            - To use an earlier step's whole output, reference "$prev" or "$prev.field".
            - Any arg value not starting with "$" is a literal.
            - For enum-like fields, use exactly one of the allowed values from the schema.
            - Provide a short rationale per step and a one-line plan summary.
            - If the request cannot be satisfied with these tools, set feasible=false,
              leave steps empty, and explain why in summary. Otherwise feasible=true.
            """;

    // toolChoice=none: model sees function definitions for awareness but cannot call them.
    // Execution is handled by the deterministic PlanExecutor, not the LLM.
    private static final OpenAiChatOptions PLANNER_OPTIONS = OpenAiChatOptions.builder()
            .toolChoice("none")
            .build();

    public PlannerService(ChatClient.Builder chatClientBuilder, ToolRegistry registry) {
        this.registry = registry;
        this.chatClient = chatClientBuilder.build();
    }

    public Plan plan(String userPrompt) {
        return doPlan(userPrompt, null);
    }

    public Plan plan(String userPrompt, String priorFailure) {
        return doPlan(userPrompt, priorFailure);
    }

    private Plan doPlan(String userPrompt, String priorFailure) {
        String user = userPrompt;
        if (priorFailure != null && !priorFailure.isBlank()) {
            user = userPrompt + "\n\nYour previous plan was REJECTED for this reason:\n"
                    + priorFailure + "\nProduce a corrected plan that fixes this.";
        }
        return chatClient.prompt()
                .system(BASE_INSTRUCTIONS)
                .user(user)
                .options(PLANNER_OPTIONS)
                .toolCallbacks(registry.callbacks())
                .call()
                .entity(Plan.class);
    }
}
