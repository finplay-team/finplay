// finplay.community.image-storage.s3.* 설정값(버킷명)을 바인딩하는 프로퍼티 record — 기본값 없이 fail-fast한다
package com.finplay.api.community.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finplay.community.image-storage.s3")
public record CommunityS3StorageProperties(String bucket) {
}
