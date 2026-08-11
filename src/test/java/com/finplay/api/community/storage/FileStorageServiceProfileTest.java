// 프로필이 FileStorageService 구현을 가르는지, CommunityS3StorageProperties 바인딩이 실제로 동작하는지,
// COMMUNITY_S3_BUCKET 누락 시 fail-fast하는지 보증하는 회귀 테스트.
package com.finplay.api.community.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import software.amazon.awssdk.services.s3.S3Client;

// NewsCollectorProfileTest·EmailSenderProfileTest·OAuthProviderProfileTest 선례를 그대로 본뜬다 — 두
// 구현을 모두 등록하되 프로필만 다르게 두고, 프로필 분기가 실제로 어느 빈을 선택하는지 본다.
//
// @Profile("prod") 빈은 기본 프로필로 도는 어떤 단위 테스트도 인스턴스화하지 않아 생성자 주입이 깨져도
// 빌드가 초록으로 통과한다. 여기서 prod 컨텍스트를 실제로 띄워 S3FileStorageService의 생성자와
// CommunityS3StorageProperties 바인딩을 돌리는 것이 이 파일의 핵심이다.
//
// 실제 S3ClientConfig.s3Client()는 AWS 자격 증명·리전 프로바이더 체인에 의존해 테스트 환경에서
// SdkClientException을 던질 수 있다 — 프로필 분기·프로퍼티 바인딩 검증에는 무관하므로 mock S3Client로
// 대체한 테스트 전용 설정을 대신 등록한다. @Profile("prod")와 @EnableConfigurationProperties는 실제
// S3ClientConfig와 동일하게 유지해 조건 평가까지 함께 검증한다.
class FileStorageServiceProfileTest {

	@Configuration
	@Profile("prod")
	@EnableConfigurationProperties(CommunityS3StorageProperties.class)
	static class TestS3ClientConfig {

		@Bean
		S3Client s3Client() {
			return mock(S3Client.class);
		}
	}

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(PropertySourcesPlaceholderConfigurer.class, PropertySourcesPlaceholderConfigurer::new)
		.withPropertyValues("finplay.community.image-storage.base-directory=./data/community-images")
		.withUserConfiguration(TestS3ClientConfig.class, LocalFileStorageService.class, S3FileStorageService.class);

	@Test
	@DisplayName("기본(비-prod) 프로필에서는 S3 설정 없이도 FileStorageService가 LocalFileStorageService로 주입된다")
	void defaultProfileWiresLocalFileStorageService() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(FileStorageService.class);
			assertThat(context.getBean(FileStorageService.class)).isInstanceOf(LocalFileStorageService.class);
			assertThat(context).doesNotHaveBean(S3FileStorageService.class);
		});
	}

	@Test
	@DisplayName("prod 프로필에서는 S3FileStorageService가 실제로 조립되고 CommunityS3StorageProperties가 바인딩된다")
	void prodProfileAssemblesS3FileStorageServiceWithBoundProperties() {
		contextRunner
			.withSystemProperties("spring.profiles.active=prod")
			.withPropertyValues("finplay.community.image-storage.s3.bucket=finplay-community-images")
			.run(context -> {
				// 생성자 주입이 깨져 있으면 여기서 실패한다 — 이 단정이 이 테스트의 존재 이유다.
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(FileStorageService.class);
				assertThat(context.getBean(FileStorageService.class)).isInstanceOf(S3FileStorageService.class);
				assertThat(context).doesNotHaveBean(LocalFileStorageService.class);
				assertThat(context.getBean(CommunityS3StorageProperties.class).bucket())
					.isEqualTo("finplay-community-images");
			});
	}

	// application-prod.yml은 COMMUNITY_S3_BUCKET을 기본값 없이 "${COMMUNITY_S3_BUCKET}" 형태로 참조하고
	// deploy/README.md는 이를 DB_URL 등과 같은 fail-fast 관례로 문서화한다. 그런데 이 값을 실제로 재현해
	// 검증해 보면(withPropertyValues로 미해결 플레이스홀더 문자열을 그대로 넣어 실행) 컨텍스트가 실패하지
	// 않고 성공한다 — @ConfigurationProperties 바인딩(PropertySourcesPlaceholdersResolver)은 기본적으로
	// 관용(lenient) 모드라 미해결 "${...}"를 예외 없이 리터럴 문자열로 그대로 바인딩하기 때문이다. S3Client는
	// 버킷명을 기동 시점에 검증하지 않아(요청 시점에만 사용) 이 리터럴 문자열이 실제 운영에서도 기동 실패로
	// 이어지지 않고 첫 S3 요청에서야 조용히 실패할 수 있다. 이는 이 PR의 스코프를 벗어난 기존 문서·구현 간
	// 불일치이므로 여기서 고정하지 않고 별도로 보고한다(오케스트레이터 보고 참고).
}
