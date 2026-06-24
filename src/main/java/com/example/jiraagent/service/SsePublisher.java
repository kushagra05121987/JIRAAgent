package com.example.jiraagent.service;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;

import java.util.Map;

@Component
public class SsePublisher {
    public void publishEvent(FluxSink<ServerSentEvent<Object>> sink, Object event) {
        sink.next(ServerSentEvent.builder().data(event instanceof String ? Map.of("message", event) : event).build());
    }
}
