package com.example.leave.api;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class LeaveApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeaveApiApplication.class, args);
    }

    /** 「今日」の基準。テストで差し替えられるように Bean にしておく。 */
    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Tokyo"));
    }
}
