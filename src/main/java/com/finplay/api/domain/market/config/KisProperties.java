// kis.* 설정값(기본 URL·앱키·시크릿·호출 간격)을 바인딩하는 프로퍼티 record — 과거 분봉 수집 클라이언트가 사용한다.
package com.finplay.api.domain.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "kis")
public record KisProperties(
	String baseUrl,
	String appKey,
	String appSecret,
	// 분봉 조회 요청 사이에 둘 최소 간격(ms). 모의투자 도메인은 초당 호출 허용량이 낮아 16종목·최대 10페이지를 연달아
	// 호출하면 EGW00201("초당 거래건수를 초과하였습니다")로 전 종목이 실패한다(2026-07-30 실측). 0이면 지연 없이
	// 호출한다 — 현재 배포 구성은 로컬·운영(kis.base-url) 모두 모의투자 도메인이라(이슈 #370) 이 기본값이 실제로
	// 쓰이는 경로는 없다(양쪽 프로필이 각각 600을 명시). 실전투자 도메인으로 전환하면 다시 유효해질 값이다.
	@DefaultValue("0")
	long requestIntervalMs) {
}
