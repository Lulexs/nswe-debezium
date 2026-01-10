package com.sinks.redis_sink.redis;

import com.sinks.redis_sink.model.KafkaCdcEvent;
import com.sinks.redis_sink.model.KafkaCdcEventKey;
import com.sinks.redis_sink.model.KafkaCdcEventValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class RedisSinkImpl implements RedisSink {

    private final StringRedisTemplate redisTemplate;
    private final Logger LOGGER = LoggerFactory.getLogger(RedisSinkImpl.class);

    public RedisSinkImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void sink(KafkaCdcEvent cdcEvent) {
        String redisKey = getRedisKeyFromCdcEvent(cdcEvent.eventKey(), cdcEvent.eventValue());

        switch (cdcEvent.eventValue().payload().op()) {
            case "c":
            case "u":
                upsertEntry(redisKey, cdcEvent.eventValue());
                break;
            case "d":
                deleteEntry(redisKey);
                break;
            default:
                LOGGER.warn("UNSUPPORTED OPERATION for KafkaCdcEvent {}", cdcEvent);
        }
    }

    private void upsertEntry(String redisKey, KafkaCdcEventValue kafkaCdcEventValue) {
        redisTemplate.boundHashOps(redisKey).putAll(kafkaCdcEventValue.payload().after());
    }

    private void deleteEntry(String redisKey) {
        boolean deleted = redisTemplate.delete(redisKey);
        LOGGER.info("Deletion of {} was {}", redisKey, deleted ? "success" : "failure");
    }

    private String getRedisKeyFromCdcEvent(KafkaCdcEventKey eventKey, KafkaCdcEventValue eventValue) {
        return new StringBuilder().append(eventValue.payload().source().schema())
                .append("-")
                .append(eventValue.payload().source().table())
                .append(":")
                .append(eventKey.payload().values().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(".")))
                .toString();
    }
}
