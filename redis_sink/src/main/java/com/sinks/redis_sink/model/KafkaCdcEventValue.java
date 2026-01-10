package com.sinks.redis_sink.model;

import java.util.Map;

public record KafkaCdcEventValue(KafkaCdcEventValuePayload payload) {

    public record KafkaCdcEventValuePayload(KafkaEventSource source,
                                             Map<String, String> before,
                                             Map<String, String> after,
                                             String op) {
    }

    public record KafkaEventSource(String schema,
                                    String table) {
    }
}
