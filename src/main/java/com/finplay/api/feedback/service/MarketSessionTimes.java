// 주식 개장·장 마감의 벽시계 경계 상수만 갖는 최소 타입(spec 012 §C-6) — Spring 빈이 아니다.
package com.finplay.api.feedback.service;

import java.time.LocalTime;

/**
 * spec §C-2가 값의 정본이다 — 여기서 다시 정하지 않는다.
 *
 * <p><b>왜 한 곳으로 모으는가.</b> 같은 09:00·15:30이 {@code PriceMoveCardService}의 클램프 기준과
 * {@code NewsMatcher}의 전장 구간 경계에 독립 선언돼 있었고, Part C의 {@code summaryScope} 판정과 Part D의
 * 하한이 세 번째·네 번째 사용처가 된다. 매도 회고·반사실의 게이트(§C-5)가 같은 15:30을 쓰므로 다섯 번째도
 * 예정돼 있다. 선언이 흩어지면 한 곳만 고치는 실수가 예외도 로그도 없이 조회 결과만 바꾼다.
 *
 * <p><b>이 값은 벽시계다 — 분봉을 찾는 값이 아니다</b> (§C-2-1). 기사·공시 구간 경계와 노출 게이트에만 쓰며
 * "첫/마지막 분봉"으로 바꾸면 안 된다. {@code 003-market-data}가 분봉 timestamp를 구간 시작 기준으로 다뤄
 * 그날 마지막 분봉이 15:29일 수 있고 첫 분봉이 09:03일 수도 있는데, 그 사정은 가격 조회
 * ({@code prevClose}·{@code atClose}·시가) 쪽 규칙이고 이 상수와 무관하다. 여기를 분봉 시각으로 바꾸면
 * 브리핑이 종목별 분봉에 묶여 전 종목 단일 질의가 성립하지 않고, 갭 카드는 첫 분봉이 늦은 날 그만큼 늦게 열린다.
 *
 * <p><b>{@code config/}가 아니라 {@code feedback/service/}에 둔다</b> (§C-6). {@code feedback.*} 설정 블록은
 * §튜닝으로 조정하는 수치를 담지만 이 둘은 조정 대상이 아니라 시장 규칙이다.
 *
 * <p>가시성을 좁힌 것은 현재·예정 사용처가 전부 이 패키지의 서비스이기 때문이다
 * ({@code StockCandleAggregator}와 같은 방침). 밖에서 필요해지면 그때 넓힌다.
 */
final class MarketSessionTimes {

	/** 정규장 개장 시각. 전장 구간 {@code [D-1 15:30, D 09:00]}의 상한이자 노출 클램프의 기준이다. */
	static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 0);

	/** 정규장 마감 시각. 전장 구간의 하한이자 {@code FULL} 범위의 경계다. */
	static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30);

	private MarketSessionTimes() {}
}
