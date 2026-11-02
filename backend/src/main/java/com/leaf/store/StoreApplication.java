package com.leaf.store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class StoreApplication {
    public static void main(String[] args) { SpringApplication.run(StoreApplication.class, args); }
}

