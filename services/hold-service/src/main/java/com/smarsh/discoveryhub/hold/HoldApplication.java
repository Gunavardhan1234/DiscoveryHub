package com.smarsh.discoveryhub.hold;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HoldApplication {
    public static void main(String[] args) {
        SpringApplication.run(HoldApplication.class, args);
    }
}
