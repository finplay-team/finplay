package com.finplay.api.domain.community.storage;

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
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(FileStorageService.class);
				assertThat(context.getBean(FileStorageService.class)).isInstanceOf(S3FileStorageService.class);
				assertThat(context).doesNotHaveBean(LocalFileStorageService.class);
				assertThat(context.getBean(CommunityS3StorageProperties.class).bucket())
					.isEqualTo("finplay-community-images");
			});
	}

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
