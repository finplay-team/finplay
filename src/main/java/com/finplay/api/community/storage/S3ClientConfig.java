// prod 프로필에서 S3Client 빈을 노출하는 설정 — 자격 증명·리전은 SDK 기본 체인에 맡긴다
package com.finplay.api.community.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.services.s3.S3Client;

// CommunityS3StorageProperties(finplay.community.image-storage.s3.*)를 여기서 활성화한다 — record 기반
// @ConfigurationProperties는 명시적으로 등록해야 빈이 된다.
@Profile("prod")
@Configuration
@EnableConfigurationProperties(CommunityS3StorageProperties.class)
public class S3ClientConfig {

	@Bean
	public S3Client s3Client() {
		return S3Client.builder().build();
	}
}
