// 테스트 전체가 공유하는 MySQL 싱글턴 컨테이너 설정 (컨텍스트마다 재기동 방지, ADR-0003)
package com.finplay.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

	static {
		MYSQL.start();
	}

	@Bean
	@ServiceConnection
	MySQLContainer mysqlContainer() {
		return MYSQL;
	}
}
