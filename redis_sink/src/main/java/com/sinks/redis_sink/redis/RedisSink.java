package com.sinks.redis_sink.redis;

import com.sinks.redis_sink.model.KafkaCdcEvent;

public interface RedisSink {

    void sink(KafkaCdcEvent cdcEvent);

}
