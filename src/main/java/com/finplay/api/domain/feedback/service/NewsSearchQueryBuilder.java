// 종목별 뉴스 검색 질의어를 조립하는 순수 계산 — 코인 이름에만 보정 접미를 붙인다.
package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
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
 * <p><b>보정은 {@code " 코인"}이 아니라 심볼이다</b> (2026-08-07 개정, 이슈 #179). 개정 전에는 이름 뒤에
 * {@code " 코인"}을 붙였는데, 실측에서 그 접미가 <b>일반 암호화폐 기사를 끌어와 이름만 쓰는 것보다도
 * 나빴다</b> — 12종목 1,200건에서 제목에 자기 이름이 든 기사가 <b>이름만 170건, {@code " 코인"}을 붙이면
 * 오히려 줄어 145건, 심볼을 붙이면 261건</b>이었다. 근거는 spec §FEED-001의 개정 문단이다.
 *
 * <p><b>외부 의존이 없다.</b> DB·시계·HTTP를 붙이지 않아 고정 픽스처로 단정할 수 있다
 * ({@code PriceMoveDetector}를 순수 함수로 두는 이유와 같다, spec §C-6).
 *
 * <p>보정 방식을 설정으로 빼지 않은 것은 §C-7에 그런 키가 없기 때문이다. 실측 결과 무관 기사 비율이 높으면
 * 코드가 아니라 spec §FEED-001을 먼저 고친다 — 이번 개정이 그 절차를 그대로 밟았다.
 */
@Component
public class NewsSearchQueryBuilder {

	// 종목명과 심볼 사이의 구분자. 붙여 쓰면 다른 단어가 된다.
	private static final String QUERY_DELIMITER = " ";

	/**
	 * 종목 하나의 뉴스 검색 질의어를 만든다. 네이버 검색 API의 {@code query} 파라미터로 그대로 나간다.
	 *
	 * <p><b>주식은 이름 그대로다</b>(spec §FEED-001). 주식 시드에는 종목명이 일반명사와 충돌하는 경우가 없어
	 * 보정할 이유가 없고, <b>실측하지 않은 시장의 규칙을 바꾸지 않는다</b>(C-005).
	 */
	public String build(Instrument instrument) {
		if (instrument.getMarket() == Market.CRYPTO) {
			return instrument.getName() + QUERY_DELIMITER + instrument.getSymbol();
		}
		return instrument.getName();
	}
}
