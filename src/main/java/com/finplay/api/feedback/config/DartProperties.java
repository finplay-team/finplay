// dart.* 설정값(OpenDART 공시검색 API 키)을 바인딩하는 프로퍼티 record — 공시 수집기가 사용한다.
package com.finplay.api.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 키 경로의 정본은 ai/specs/012-ai-feedback/spec.md §C-7이다. NaverSearchProperties와 같은 이유로 최상위에
// 두고 @DefaultValue를 붙이지 않는다 — 값은 DART_API_KEY 환경변수에서만 온다.
//
// 값이 없으면 빈 문자열로 바인딩된다. NaverSearchProperties와 같은 이유로 그 빈 값이 수집기 선택을 바꾸지는
// 않는다 — DartDisclosureCollector가 @Profile("prod"), FakeDisclosureCollector가 @Profile("!prod")로 프로필
// 단독으로 갈린다. 로컬·테스트는 키와 무관하게 Fake가 공시 없이 뉴스만으로 진행시키고, 운영에서 키가 비면
// 실제 수집기가 그대로 떠 호출이 실패하며 그 종목만 건너뛴다(§실패 처리).
//
// 2026-08-17 정정 (이슈 #410) — 그전까지 "값이 없으면 Fake가 반환한다"고 적었으나 사실이 아니었다.
//
// 공시는 주식만 해당한다(FEED-001).
@ConfigurationProperties(prefix = "dart")
public record DartProperties(String apiKey) {
}
