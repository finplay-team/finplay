// Testcontainers를 붙여 로컬에서 앱을 실행하는 테스트용 진입점
package com.finplay.api;

import org.springframework.boot.SpringApplication;

public class TestFinPlayApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(FinPlayApiApplication::main)
			.with(TestcontainersConfiguration.class)
			.run(args);
	}
}
