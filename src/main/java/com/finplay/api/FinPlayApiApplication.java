// FinPlay API 서버의 진입점
package com.finplay.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling: SseEmitterRegistry의 20초 heartbeat @Scheduled 작업(이슈 #18)을 활성화한다.
@EnableScheduling
@SpringBootApplication
public class FinPlayApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(FinPlayApiApplication.class, args);
	}
}
