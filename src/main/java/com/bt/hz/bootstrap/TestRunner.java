package com.bt.hz.bootstrap;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class TestRunner implements CommandLineRunner {

    private final HazelcastInstance hz;

    public TestRunner(HazelcastInstance hz) {
        this.hz = hz;
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("================================================================");
        System.out.println("WAITING 15 SECONDS FOR JET PIPELINES TO INITIALIZE...");
        System.out.println("================================================================");
        Thread.sleep(15000);

        IMap<String, Object> map = hz.getMap("bt_sessions");

        Map<String, String> data = new HashMap<>();
        data.put("LOGIN", "test_login");
        data.put("LOGIN_TYPE", "WEB");
        data.put("USER_INFO", "{\"userId\":\"1\", \"username\":\"u\"}");

        System.out.println("================================");
        System.out.println("INSERTING TEST DATA INTO bt_sessions");
        System.out.println("================================");

        map.put("test_spring_session", data);
    }
}
