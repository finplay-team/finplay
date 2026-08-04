// 변동 구간 카드의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드는 <b>필요해진 이슈에서 하나씩</b> 더한다 — {@code MarketNewsItemRepository}와 같은 이유다.
 * 보유 구간 카드 조회(#6), 코인 쿨다운·일일 상한 카운트(#8)는 각자의 이슈가 그 이슈의 완료 조건과 함께 추가한다.
 */
public interface PriceMoveEventRepository extends JpaRepository<PriceMoveEvent, Long> {

	/**
	 * 같은 카드가 이미 있는지 본다 — 축이 테이블의
	 * {@code UNIQUE(instrument_id, origin_trade_date, event_type, window_start)}와 <b>정확히 같다</b>
	 * (§데이터 모델). 배치가 같은 서비스 날짜에 두 번 돌아도 중복 생성되지 않게 하는 판정이다(§실패 처리).
	 *
	 * <p><b>{@code eventType}을 빼면 안 된다.</b> 장중 루프의 첫 후보는 {@code windowStart = 09:00}인데
	 * 시가 갭 카드의 {@code windowStart}도 첫 분봉 시각이라, 축이 겹치는 순간 나중에 만들어지는 쪽이
	 * "이미 있다"로 판정되어 <b>조용히 사라진다.</b>
	 *
	 * <p>이 판정을 서술 생성보다 <b>먼저</b> 하는 이유는 비용이다 — 뒤로 미루면 재실행 때마다 카드 수만큼
	 * LLM을 다시 부르고 그 결과를 유니크 제약이 버린다.
	 */
	boolean existsByInstrumentIdAndOriginTradeDateAndEventTypeAndWindowStart(
		Long instrumentId, LocalDate originTradeDate, PriceMoveEventType eventType, LocalTime windowStart);
}
