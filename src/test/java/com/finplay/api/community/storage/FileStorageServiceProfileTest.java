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

	// 이슈 #335 — CommunityS3StorageProperties의 컴팩트 생성자 가드가 미해결 플레이스홀더·공백 bucket을
	// 실제로 막는지 고정한다. @ConfigurationProperties 바인딩은 기본적으로 관용(lenient) 모드라 가드가
	// 없으면 "${COMMUNITY_S3_BUCKET}" 같은 미해결 문자열도 예외 없이 그대로 바인딩된다.
	@Test
	@DisplayName("COMMUNITY_S3_BUCKET이 미해결 플레이스홀더로 남아 있으면 prod 컨텍스트 기동이 실패한다")
	void prodProfileFailsFastWhenBucketPlaceholderUnresolved() {
		contextRunner
			.withSystemProperties("spring.profiles.active=prod")
			.withPropertyValues("finplay.community.image-storage.s3.bucket=${COMMUNITY_S3_BUCKET}")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
			});
	}

	@Test
	@DisplayName("COMMUNITY_S3_BUCKET이 공백이면 prod 컨텍스트 기동이 실패한다")
	void prodProfileFailsFastWhenBucketBlank() {
		contextRunner
			.withSystemProperties("spring.profiles.active=prod")
			.withPropertyValues("finplay.community.image-storage.s3.bucket=   ")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
			});
	}

	@Test
	@DisplayName("s3.bucket 프로퍼티 키 자체가 없으면(null 바인딩) prod 컨텍스트 기동이 실패한다")
	void prodProfileFailsFastWhenBucketPropertyMissing() {
		contextRunner
			.withSystemProperties("spring.profiles.active=prod")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
			});
	}
}
