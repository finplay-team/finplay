// 시장·거래일 단위 개장 전 브리핑의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.MarketBriefing;
import com.finplay.api.market.domain.Market;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 브리핑 <b>조회</b>는 {@code items}를 함께 질의해 상태값을 판정해야 하고(§C-4) 그 형태가 응답 DTO와 함께
 * 정해지므로, 조회 질의는 그 이슈에서 완료 조건과 함께 추가한다. 여기 있는 것은 생성 쪽 중복 판정 하나다.
 */
public interface MarketBriefingRepository extends JpaRepository<MarketBriefing, Long> {

	/**
	 * 그 {@code (시장, 원본 거래일)} 브리핑이 이미 있는지 본다 — 축은 {@code UNIQUE(market, origin_trade_date)}와
	 * 같다 (§데이터 모델).
	 *
	 * <p>주식은 UPSERT가 아니라 "존재 시 건너뜀"이다. 이유는
	 * {@code InstrumentNewsSummaryRepository.existsByInstrumentIdAndOriginTradeDateAndScope}와 같다.
	 */
	boolean existsByMarketAndOriginTradeDate(Market market, LocalDate originTradeDate);
}
