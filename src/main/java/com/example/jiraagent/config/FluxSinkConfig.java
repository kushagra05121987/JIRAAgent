package com.example.jiraagent.config;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.context.WebApplicationContext;
import reactor.core.Disposable;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;

import java.util.Map;
import java.util.function.LongConsumer;

@Configuration
public class FluxSinkConfig {
    @Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    @Bean
    Map<String, Object> bridge() {
        Sinks.Many<ServerSentEvent<Object>> manyUnicast = Sinks.many().unicast().onBackpressureBuffer();
        FluxSink<ServerSentEvent<Object>> sink = new FluxSink<>() {
            @Override
            public FluxSink<ServerSentEvent<Object>> next(ServerSentEvent<Object> t) {
                manyUnicast.tryEmitNext(t);
                return null;
            }

            @Override
            public void error(Throwable e) {
                manyUnicast.tryEmitError(e);
            }

            @Override
            public void complete() {
                manyUnicast.tryEmitComplete();
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
                return manyUnicast.currentSubscriberCount() == 0;
            }

            @Override
            public Context currentContext() {
                return Context.empty();
            }
        };
        return Map.of("sink", sink, "manyUniCast", manyUnicast);
    }

}