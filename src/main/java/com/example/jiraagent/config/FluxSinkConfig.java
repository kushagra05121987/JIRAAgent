package com.example.jiraagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;

import java.util.Map;
import java.util.function.LongConsumer;

@Configuration
public class FluxSinkConfig {
    @Bean
    Map<String, Object> multiCastSink() {
        Sinks.Many<ServerSentEvent<Object>> many = Sinks.many().unicast().onBackpressureBuffer();
        FluxSink<ServerSentEvent<Object>> bridge = new FluxSink<>() {
            @Override
            public FluxSink<ServerSentEvent<Object>> next(ServerSentEvent<Object> t) {
                many.tryEmitNext(t);
                return null;
            }

            @Override
            public void error(Throwable e) {
                many.tryEmitError(e);
            }

            @Override
            public void complete() {
                many.tryEmitComplete();
            }

            @Override
            public FluxSink<ServerSentEvent<Object>> onRequest(LongConsumer c) {
                return this;
            }

            @Override
            public FluxSink<ServerSentEvent<Object>> onCancel(Disposable d) {
                return this;
            }

            @Override
            public FluxSink<ServerSentEvent<Object>> onDispose(Disposable d) {
                return this;
            }

            @Override
            public long requestedFromDownstream() {
                return Long.MAX_VALUE;
            }

            @Override
            public boolean isCancelled() {
                return many.currentSubscriberCount() == 0;
            }

            @Override
            public Context currentContext() {
                return Context.empty();
            }
        };
        return Map.of("many", many, "sink", bridge);
    }

}
