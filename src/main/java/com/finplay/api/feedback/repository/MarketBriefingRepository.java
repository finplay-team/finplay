// 시장·거래일 단위 개장 전 브리핑의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.MarketBriefing;
import com.finplay.api.market.domain.Market;
import java.time.LocalDate;
import java.util.Optional;
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

	/**
	 * 그 {@code (시장, 원본 거래일)} 브리핑 행을 가져온다 — 축은 위 {@code exists}와 같은 유니크 키다.
	 *
	 * <p><b>존재 여부가 아니라 행 자체를 받아야 한다.</b> 저장된 행만으로는 {@code EMPTY}와
	 * {@code UNAVAILABLE}이 구분되지 않는다 — 둘 다 {@code summary}가 {@code NULL}이다(§C-4). 행이 없으면
	 * {@code EMPTY}(판정 순서 4번), 행은 있는데 {@code summary}가 {@code null}이면 {@code UNAVAILABLE}
	 * (5번)이며 <b>두 경우 모두 {@code items}는 채운다.</b>
	 */
	Optional<MarketBriefing> findByMarketAndOriginTradeDate(Market market, LocalDate originTradeDate);

	/**
	 * 그 시장의 <b>{@code generated_at}이 가장 최신인 행 1건</b>을 가져온다 — 코인 조회 경로다.
	 *
	 * <p>"오늘 날짜 행"으로 찾으면 안 되는 이유는
	 * {@code InstrumentNewsSummaryRepository.findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc}와
	 * 같다. 인덱스는 V13의 {@code INDEX(market, generated_at)}가 이미 있다(§데이터 모델).
	 */
	Optional<MarketBriefing> findFirstByMarketOrderByGeneratedAtDescIdDesc(Market market);
}
