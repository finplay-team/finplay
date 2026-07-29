// kis.* 설정값(기본 URL·앱키·시크릿)을 바인딩하는 프로퍼티 record — 과거 분봉 수집 클라이언트가 사용한다.
package com.finplay.api.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kis")
public record KisProperties(String baseUrl, String appKey, String appSecret) {
}
