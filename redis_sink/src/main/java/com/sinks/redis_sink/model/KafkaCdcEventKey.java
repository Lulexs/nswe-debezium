package com.sinks.redis_sink.model;

import java.util.Map;

public record KafkaCdcEventKey(Map<String, Object> payload) {

}
