// 테스트 전체가 공유하는 MySQL 싱글턴 컨테이너 설정 (컨텍스트마다 재기동 방지, ADR-0003)
package com.finplay.api;

import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	// max_connections를 올린다 (이슈 #119). 컨테이너가 실행 내내 살아남게 되면서 캐시된 Spring 컨텍스트마다
	// Hikari 풀이 그대로 유지되는데, Boot 기본값은 풀 하나당 커넥션 10개(minimumIdle = maximumPoolSize)라
	// 컨텍스트가 열대여섯 개만 쌓여도 MySQL 기본 한도 151을 넘는다(실측 최대 153 → "Too many connections").
	// 전에는 컨테이너가 중간에 교체되며 접속이 함께 끊겨 이 한도가 드러나지 않았다.
	private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
		.withCommand("mysqld", "--max-connections=1000");

	// Redis도 MySQL과 같은 static 싱글턴으로 공유한다 (컨텍스트마다 재기동 방지, ADR-0003). 태그 고정.
	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4"))
		.withExposedPorts(6379);

	static {
		MYSQL.start();
		REDIS.start();
	}

	// 컨테이너 자체를 @Bean으로 노출하지 않는다 (이슈 #119). Testcontainers 컨테이너는 Startable이라
	// 빈으로 등록하면 Spring이 생명주기를 관리해 컨텍스트가 닫힐 때 stop()한다. 그러면 다음 컨텍스트가
	// refresh하면서 컨테이너를 새 포트로 다시 띄우고, 그 전에 만들어져 캐시된 컨텍스트들은 옛 포트를
	// 물고 있어 전부 연결에 실패한다(실행 도중 컨테이너 ID와 포트가 바뀌는 것을 0.8초 간격 관측으로 확인).
	// ConnectionDetails는 Startable이 아니므로 Spring이 stop할 수단이 없고, 무엇이 컨텍스트를 닫든
	// 컨테이너는 JVM이 끝날 때까지(Ryuk이 정리할 때까지) 같은 포트로 살아남는다.
	//
	// @DynamicPropertySource로 포트를 넘기는 방식은 이 저장소에서 쓸 수 없다 — @Import된 설정 클래스에
	// 두면 수집되지 않는다(PR #110, build.gradle 주석 참고).
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
