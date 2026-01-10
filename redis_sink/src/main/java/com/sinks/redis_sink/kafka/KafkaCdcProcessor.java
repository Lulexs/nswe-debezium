package com.sinks.redis_sink.kafka;

import com.sinks.redis_sink.config.CdcKafkaProperties;
import com.sinks.redis_sink.model.KafkaCdcEvent;
import com.sinks.redis_sink.model.KafkaCdcEventKey;
import com.sinks.redis_sink.model.KafkaCdcEventValue;
import com.sinks.redis_sink.redis.RedisSink;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.KStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class KafkaCdcProcessor {

    private final CdcKafkaProperties properties;
    private final ObjectMapper objectMapper;
    private final Logger LOGGER = LoggerFactory.getLogger(KafkaCdcProcessor.class);
    private final RedisSink redisSink;

    public KafkaCdcProcessor(CdcKafkaProperties properties, ObjectMapper objectMapper, RedisSink redisSink) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.redisSink = redisSink;
    }

    @Bean
    public KStream<String, String> mergedCdcStream(StreamsBuilder builder) {
        KStream<String, String> mergedStream = builder.stream(properties.getTopics());

        mergedStream.foreach((k, v) -> {
            try {
                KafkaCdcEventKey key = objectMapper.readValue(k, KafkaCdcEventKey.class);
                KafkaCdcEventValue value = objectMapper.readValue(v, KafkaCdcEventValue.class);
                KafkaCdcEvent cdcEvent = new KafkaCdcEvent(key, value);
                LOGGER.info("Read event from kafka {}", cdcEvent);
                redisSink.sink(cdcEvent);
            } catch (JacksonException e) {
                LOGGER.error("Failed to deserialize event {}", v, e);
            }
        });

        return mergedStream;
    }
}
