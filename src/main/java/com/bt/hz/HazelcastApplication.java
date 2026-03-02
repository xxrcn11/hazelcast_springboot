package com.bt.hz;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HazelcastApplication {

    public static void main(String[] args) {
        // Hazelcast가 STARTED 된 후에 SpringBoot 실행
        System.out.println("====== Starting Hazelcast Instance before Spring Boot ======");

        Config config = Config.load();
        // Properties already defined in hazelcast.xml are automatically loaded.

        // Start Hazelcast explicitly before Spring
        HazelcastInstance hazelcastInstance = Hazelcast.newHazelcastInstance(config);

        System.out.println("====== Hazelcast Instance STARTED ======");

        // Spring Boot is started by SpringBootStrapListener (configured in
        // hazelcast.xml)
        // after Hazelcast successfully starts.
        // Therefore, we do not start it here to prevent running two Spring Contexts.
    }
}
