// 주식 개장·장 마감의 벽시계 경계와 코인 롤링 창 상수만 갖는 최소 타입(spec 012 §C-6) — Spring 빈이 아니다.
package com.finplay.api.feedback.service;

import java.time.Duration;
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
 * <p><b>주식 두 값과 코인 창의 성격이 다르다.</b> 위 둘은 <b>벽시계 시각</b>이라 원본 거래일에 붙여 절대 시각을
 * 만들지만, {@code ROLLING_WINDOW}는 <b>길이</b>라 조회·배치 시각에서 거꾸로 뺀다 — 코인은 거래일 경계가
 * 없어 기준점이 고정 시각이 아니라 "지금"이기 때문이다. 한 타입에 함께 두는 것은 셋 다 <b>§C-2가 정한 시장
 * 규칙</b>이고 같은 이유로 흩어지면 안 되기 때문이지, 쓰는 방식이 같아서가 아니다.
 *
 * <p><b>{@code config/}가 아니라 {@code feedback/service/}에 둔다</b> (§C-6). {@code feedback.*} 설정 블록은
 * §튜닝으로 조정하는 수치를 담지만 이 셋은 조정 대상이 아니라 시장 규칙이다.
 *
 * <p>가시성을 좁힌 것은 현재·예정 사용처가 전부 이 패키지의 서비스이기 때문이다
 * ({@code StockCandleAggregator}와 같은 방침). 밖에서 필요해지면 그때 넓힌다.
 */
final class MarketSessionTimes {

	/** 정규장 개장 시각. 전장 구간 {@code [D-1 15:30, D 09:00]}의 상한이자 노출 클램프의 기준이다. */
	static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 0);

	/** 정규장 마감 시각. 전장 구간의 하한이자 {@code FULL} 범위의 경계다. */
	static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30);

	/**
	 * 코인 {@code ROLLING_24H}의 창 길이 (§C-2).
	 *
	 * <p><b>코인은 거래일 경계가 없어 조회 시각 기준 롤링이다.</b> 주식의 {@code 전장}·{@code FULL}이 원본
	 * 거래일에 붙은 고정 구간인 것과 달리 기준점이 "지금"이라, 쓸 때는 시각에서 이 길이를 뺀다
	 * ({@code now.minus(ROLLING_WINDOW)}).
	 *
	 * <p><b>세 곳이 이 값을 쓴다</b> — 코인 요약 생성·코인 브리핑 생성·두 조회의 코인 분기다. 한 곳만 바뀌면
	 * <b>요약이 다루는 창과 화면 목록의 창이 갈리는데 예외도 로그도 남지 않는다.</b> 사용자에게는 목록에 있는
	 * 기사가 요약에 언급되지 않는(또는 그 반대) 상태로만 보인다.
	 *
	 * <p><b>설정값이 아니다.</b> §튜닝으로 조정하는 수치가 아니라 §C-2가 정의한 범위 자체이며, 위 두 시각과
	 * 같은 성격이라 {@code feedback.*} 블록에 두지 않는다.
	 */
	static final Duration ROLLING_WINDOW = Duration.ofHours(24);

	private MarketSessionTimes() {}
}
