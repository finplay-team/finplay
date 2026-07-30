// kis.* 설정값(기본 URL·앱키·시크릿·호출 간격)을 바인딩하는 프로퍼티 record — 과거 분봉 수집 클라이언트가 사용한다.
package com.finplay.api.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "kis")
public record KisProperties(
	String baseUrl,
	String appKey,
	String appSecret,
	// 분봉 조회 요청 사이에 둘 최소 간격(ms). 모의투자 도메인은 초당 호출 허용량이 낮아 16종목·최대 10페이지를 연달아
	// 호출하면 EGW00201("초당 거래건수를 초과하였습니다")로 전 종목이 실패한다(2026-07-30 실측). 0이면 지연 없이
	// 호출하며, 기존 동작(실전투자 도메인 기준)을 그대로 유지하는 기본값이다.
	@DefaultValue("0")
	long requestIntervalMs) {
}
