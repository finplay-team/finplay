// prod 프로필에서 S3Client 빈을 노출하는 설정 — 자격 증명·리전은 SDK 기본 체인에 맡긴다
package com.finplay.api.community.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.services.s3.S3Client;

@Profile("prod")
@Configuration
public class S3ClientConfig {

	@Bean
	public S3Client s3Client() {
		return S3Client.builder().build();
	}
}
