package com.bt.hz.config;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HazelcastConfig {

    @Bean
    public HazelcastInstance hazelcastInstance() {
        // Return the first instance that was already started in main() before Spring context initialization
        return Hazelcast.getAllHazelcastInstances().iterator().next();
    }
}
