package com.example.jiraagent.guardrail;

import com.example.jiraagent.model.PlanRequest;
import com.example.jiraagent.service.SsePublisher;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.regex.Pattern;


@Component
public class InputGuardrail {

    private final SsePublisher ssePublisher;
    InputGuardrail(SsePublisher ssePublisher) {
        this.ssePublisher = ssePublisher;
    }

    private static final int MAX_LEN = 2000;

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)ignore (all |the )?(previous|prior|above) (instructions|prompts?)"),
            Pattern.compile("(?i)disregard (all |the )?(previous|prior|above)"),
            Pattern.compile("(?i)you are now"),
            Pattern.compile("(?i)system prompt"),
            Pattern.compile("(?i)reveal (your )?(system )?(prompt|instructions)"),
            Pattern.compile("(?i)act as (an? )?(dan|developer mode|unrestricted)"),
            Pattern.compile("(?i)pretend (you are|to be)")
    );

    private static final List<Pattern> ON_TOPIC_PATTERNS = List.of(
            Pattern.compile("(?i)jira"),
            Pattern.compile("(?i)\\bissue(s)?\\b"),
            Pattern.compile("(?i)\\bticket(s)?\\b"),
            Pattern.compile("(?i)\\bstatus\\b"),
            Pattern.compile("(?i)\\bassign"),
            Pattern.compile("(?i)in progress|to ?do|done|in review")
    );

    public GuardrailResult check(PlanRequest request, FluxSink<ServerSentEvent<Object>> sink) {
        String prompt = request == null ? null : request.prompt();

        if (prompt == null || prompt.isBlank()) {
            ssePublisher.publishEvent(sink, "Prompt is empty");
            return GuardrailResult.block("Prompt is empty.");
        }
        if (prompt.length() > MAX_LEN) {
            ssePublisher.publishEvent(sink, "Prompt exceeds the maximum length of " + MAX_LEN +" characters.");
            return GuardrailResult.block("Prompt exceeds the maximum length of " + MAX_LEN + " characters.");
        }

        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(prompt).find()) {
                ssePublisher.publishEvent(sink, "Prompt appears to contain an instruction-override / injection attempt and was blocked");
                return GuardrailResult.block(
                        "Prompt appears to contain an instruction-override / injection attempt and was blocked.");
            }
        }

        boolean onTopic = ON_TOPIC_PATTERNS.stream().anyMatch(p -> p.matcher(prompt).find());
        if (!onTopic) {
            ssePublisher.publishEvent(sink, "Prompt does not appear to describe a Jira operation. " +
                    "Please describe a Jira-related task (fetching issues, changing status, assigning users).");
            return GuardrailResult.block(
                    "Prompt does not appear to describe a Jira operation. " +
                    "Please describe a Jira-related task (fetching issues, changing status, assigning users).");
        }

        return GuardrailResult.pass();
    }
}
