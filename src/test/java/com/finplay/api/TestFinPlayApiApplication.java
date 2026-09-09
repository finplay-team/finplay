package com.finplay.api;

import org.springframework.boot.SpringApplication;

public class TestFinPlayApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(FinPlayApiApplication::main)
			.with(TestcontainersConfiguration.class)
			.run(args);
	}
}
