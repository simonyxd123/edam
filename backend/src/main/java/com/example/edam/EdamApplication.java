package com.example.edam;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * EDAM 后端应用启动类
 */
@SpringBootApplication
@MapperScan("com.example.edam.repository")
@EnableAsync
@EnableScheduling
public class EdamApplication {

    public static void main(String[] args) {
        SpringApplication.run(EdamApplication.class, args);
    }
}