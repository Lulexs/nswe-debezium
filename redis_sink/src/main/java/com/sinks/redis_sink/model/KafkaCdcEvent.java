package com.sinks.redis_sink.model;

public record KafkaCdcEvent(KafkaCdcEventKey eventKey,
                            KafkaCdcEventValue eventValue) {
}
