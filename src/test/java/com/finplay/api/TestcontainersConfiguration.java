package com.finplay.api;

import java.util.Map;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
		.withTmpFs(Map.of("/var/lib/mysql", "rw"));

	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4"))
		.withExposedPorts(6379);

	static {
		MYSQL.start();
		REDIS.start();
	}

	@Bean
	JdbcConnectionDetails mysqlConnectionDetails() {
		return new JdbcConnectionDetails() {
			@Override
			public String getUsername() {
				return MYSQL.getUsername();
			}

			@Override
			public String getPassword() {
				return MYSQL.getPassword();
			}

			@Override
			public String getJdbcUrl() {
				return MYSQL.getJdbcUrl();
			}
		};
	}

	@Bean
	DataRedisConnectionDetails redisConnectionDetails() {
		return new DataRedisConnectionDetails() {
			@Override
			public Standalone getStandalone() {
				return Standalone.of(REDIS.getHost(), REDIS.getFirstMappedPort());
			}
		};
	}
}
