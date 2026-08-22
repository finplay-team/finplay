// naver-search.* 설정값(네이버 검색 API 클라이언트 ID·시크릿)을 바인딩하는 프로퍼티 record — 뉴스 수집기가 사용한다.
package com.finplay.api.domain.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 키 경로의 정본은 ai/specs/012-ai-feedback/spec.md §C-7이다. 자격증명이라 feedback.* 안이 아니라 최상위에
// 두며(kis.app-key와 같은 형태), 시크릿이므로 @DefaultValue를 붙이지 않는다 — 값은 application.yml의 빈
// 플레이스홀더를 거쳐 NAVER_SEARCH_* 환경변수에서만 온다(conventions.md 시크릿 규칙).
//
// oauth.naver(NAVER_CLIENT_ID·NAVER_CLIENT_SECRET)와 별개 애플리케이션의 키다(§외부 API 호출 상세).
// 로그인용 값을 재사용하면 검색과 로그인 중 하나가 조용히 깨지므로 프리픽스를 naver가 아니라 naver-search로 둔다.
//
// 값이 없으면 빈 문자열로 바인딩된다. 그 빈 값이 수집기 선택을 바꾸지는 않는다 — 어느 수집기가 뜨는지는
// 프로필 단독으로 정해진다(NaverNewsCollector가 @Profile({"prod","news-real"}), FakeNewsCollector가
// @Profile("!prod & !news-real")). @ConditionalOnProperty도 키 공백 검사도 없다.
//
// 그래서 로컬·테스트에서는 키가 있든 없든 Fake가 뜨고, 운영 프로필에서는 키가 비어도 실제 수집기가 그대로 떠
// 매 호출이 401로 실패하며 §실패 처리의 "그 종목만 건너뜀"으로 흡수된다(NaverNewsCollector javadoc).
//
// 2026-08-17 정정 (이슈 #410) — 그전까지 이 자리는 "값이 없으면 실제 수집기 대신 Fake가 빈 목록을 반환한다"고
// 적었으나 사실이 아니었다. 운영에서 키가 빠진 상태를 "Fake가 대신 돈다"로 오해하면, 30분마다 34종목이 조용히
// 401로 실패하는 상태를 정상으로 읽게 된다(이슈 #273 류의 오진단).
@ConfigurationProperties(prefix = "naver-search")
public record NaverSearchProperties(String clientId, String clientSecret) {
}
