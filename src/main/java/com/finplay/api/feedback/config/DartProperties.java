// dart.* 설정값(OpenDART 공시검색 API 키)을 바인딩하는 프로퍼티 record — 공시 수집기가 사용한다.
package com.finplay.api.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 키 경로의 정본은 docs/specs/012-ai-feedback/spec.md §C-7이다. NaverSearchProperties와 같은 이유로 최상위에
// 두고 @DefaultValue를 붙이지 않는다 — 값은 DART_API_KEY 환경변수에서만 온다.
//
// 값이 없으면 빈 문자열로 바인딩되고, 그때는 실제 수집기 대신 Fake가 빈 목록을 반환해 공시 없이 뉴스만으로
// 진행한다(§실패 처리). 공시는 주식만 해당한다(FEED-001).
@ConfigurationProperties(prefix = "dart")
public record DartProperties(String apiKey) {
}
