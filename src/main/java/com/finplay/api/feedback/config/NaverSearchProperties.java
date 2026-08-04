// naver-search.* 설정값(네이버 검색 API 클라이언트 ID·시크릿)을 바인딩하는 프로퍼티 record — 뉴스 수집기가 사용한다.
package com.finplay.api.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 키 경로의 정본은 docs/specs/012-ai-feedback/spec.md §C-7이다. 자격증명이라 feedback.* 안이 아니라 최상위에
// 두며(kis.app-key와 같은 형태), 시크릿이므로 @DefaultValue를 붙이지 않는다 — 값은 application.yml의 빈
// 플레이스홀더를 거쳐 NAVER_SEARCH_* 환경변수에서만 온다(conventions.md 시크릿 규칙).
//
// oauth.naver(NAVER_CLIENT_ID·NAVER_CLIENT_SECRET)와 별개 애플리케이션의 키다(§외부 API 호출 상세).
// 로그인용 값을 재사용하면 검색과 로그인 중 하나가 조용히 깨지므로 프리픽스를 naver가 아니라 naver-search로 둔다.
//
// 값이 없으면 빈 문자열로 바인딩되고, 그때는 실제 수집기 대신 Fake가 빈 목록을 반환한다(§실패 처리).
@ConfigurationProperties(prefix = "naver-search")
public record NaverSearchProperties(String clientId, String clientSecret) {
}
