// 테스트 전체가 공유하는 MySQL 싱글턴 컨테이너 설정 (컨텍스트마다 재기동 방지, ADR-0003)
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

	// max_connections는 MySQL 기본값(151)을 쓴다. 이슈 #119에서 1000으로 올렸던 것을 이슈 #134에서 되돌렸다 —
	// 한도를 늦추는 대신 보유량 자체를 없앴기 때문이다. 캐시된 컨텍스트마다 Hikari 풀이 커넥션 10개를 계속
	// 쥐고 있던 것이 원인이었고(Boot 기본값 minimumIdle = maximumPoolSize = 10), build.gradle에서 테스트에만
	// minimum-idle=0 + idle-timeout=10초를 주어 노는 커넥션이 반납되게 했다.
	// 실측: 최대 동시 접속 276 → 16 (MySQL의 Max_used_connections 고수위 값, 이슈 #134).
	private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
		// 데이터 디렉터리를 tmpfs(램)에 둔다. 진짜 MySQL 8.4 그대로이고 저장 위치만 바뀌며,
		// 테스트 DB는 실행이 끝나면 버리므로 잃을 데이터가 없다. 실측 사용량 212MB.
		.withTmpFs(Map.of("/var/lib/mysql", "rw"));

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
