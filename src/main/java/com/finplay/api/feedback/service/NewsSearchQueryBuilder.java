// 종목별 뉴스 검색 질의어를 조립하는 순수 계산 — 코인 이름에만 보정 접미를 붙인다.
package com.finplay.api.feedback.service;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import org.springframework.stereotype.Component;

/**
 * 규칙의 정본은 spec §FEED-001이다. <b>주식 질의어는 {@code instruments.name} 그대로이고, 보정은 코인에만
 * 붙는다.</b>
 *
 * <p>코인에 보정을 붙이는 이유는 시드 종목명이 일반명사·인명과 겹치기 때문이다 — {@code 리플}·{@code 에이다}·
 * {@code 트론}·{@code 폴카닷}·{@code 체인링크}는 이름만으로 검색하면 무관 기사가 그대로 딸려 온다.
 * 근거 0건은 카드를 막지만 <b>오탐 근거는 아무것도 막지 않는다</b> — 무관 기사가 제목·URL로 노출되고
 * LLM 입력에도 들어간다. 그래서 이 조립과 {@code NewsTitleFilter}가 수집 단계의 유일한 방어선이다.
 *
 * <p><b>이미 '코인'으로 끝나는 이름에도 그대로 붙인다.</b> 시드에는 {@code 비트코인}·{@code 도지코인}·
 * {@code 비트코인캐시}가 있어 결과가 {@code 비트코인 코인}처럼 어색해 보이지만, 그것이 spec이 직접 든 예다.
 * 예외를 두면 spec에 없는 규칙을 코드가 새로 만드는 셈이고, 네이버 검색은 두 토큰을 함께 받아도 결과가
 * 좁아질 뿐 깨지지 않는다.
 *
 * <p><b>외부 의존이 없다.</b> DB·시계·HTTP를 붙이지 않아 고정 픽스처로 단정할 수 있다
 * ({@code PriceMoveDetector}를 순수 함수로 두는 이유와 같다, spec §C-6).
 *
 * <p>보정 문자열을 설정으로 빼지 않은 것은 §C-7에 그런 키가 없기 때문이다. 실측 결과 무관 기사 비율이 높으면
 * 코드가 아니라 spec §FEED-001·§C-7을 먼저 고친다 (§튜닝의 "코인 질의어 보정" 행).
 */
@Component
public class NewsSearchQueryBuilder {

	// spec §FEED-001의 확정값. 앞의 공백까지가 값이다 — 종목명과 붙여 쓰면 다른 단어가 된다.
	private static final String CRYPTO_QUERY_SUFFIX = " 코인";

	/**
	 * 종목 하나의 뉴스 검색 질의어를 만든다. 네이버 검색 API의 {@code query} 파라미터로 그대로 나간다.
	 */
	public String build(Instrument instrument) {
		if (instrument.getMarket() == Market.CRYPTO) {
			return instrument.getName() + CRYPTO_QUERY_SUFFIX;
		}
		return instrument.getName();
	}
}
