package com.finplay.api.domain.community.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.services.s3.S3Client;

@Profile("prod")
@Configuration
@EnableConfigurationProperties(CommunityS3StorageProperties.class)
public class S3ClientConfig {

	@Bean
	public S3Client s3Client() {
		return S3Client.builder().build();
	}
}
