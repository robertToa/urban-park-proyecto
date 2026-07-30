package com.urbanpark.ia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class IaApplication {
    public static void main(String[] args) {
        SpringApplication.run(IaApplication.class, args);
    }
}
