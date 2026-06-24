package com.example.jiraagent.service;

import com.example.jiraagent.exec.ToolRegistry;
import com.example.jiraagent.model.Plan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

@Service
public class PlannerService {

    private final ChatClient chatClient;
    private final ToolRegistry registry;

    private static final String BASE_INSTRUCTIONS = """
            You are a planning component for a tool-using agent.
            Convert the user's request into an ordered plan of tool-execution requests.
            You never execute anything yourself.

            Use ONLY these tools (name -> description and JSON input schema):

            %s

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

    public PlannerService(ChatClient.Builder chatClientBuilder, ToolRegistry registry) {
        this.registry = registry;
        this.chatClient = chatClientBuilder.build();
    }

    public Plan plan(String userPrompt) {
        String system = BASE_INSTRUCTIONS.formatted(renderCatalogue());
        return chatClient.prompt()
                .system(system)
                .user(userPrompt)
                .call()
                .entity(Plan.class);
    }

    public Plan plan(String userPrompt, String priorFailure) {
        String system = BASE_INSTRUCTIONS.formatted(renderCatalogue());
        String user = userPrompt;
        if (priorFailure != null && !priorFailure.isBlank()) {
            user = userPrompt + "\n\nYour previous plan was REJECTED for this reason:\n"
                    + priorFailure + "\nProduce a corrected plan that fixes this.";
        }
        return chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .entity(Plan.class);
    }

    private String renderCatalogue() {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (ToolDefinition def : registry.definitions()) {
            sb.append(i++).append(". ").append(def.name()).append("\n")
                    .append("   description: ").append(def.description()).append("\n")
                    .append("   input schema: ").append(def.inputSchema()).append("\n");
        }
        return sb.toString();
    }
}