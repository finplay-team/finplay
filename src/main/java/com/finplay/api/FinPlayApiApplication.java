package com.finplay.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class FinPlayApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(FinPlayApiApplication.class, args);
	}
}
