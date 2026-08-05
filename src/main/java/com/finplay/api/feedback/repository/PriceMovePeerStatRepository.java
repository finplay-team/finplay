// 카드별 집단 행동 집계의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.PriceMovePeerStat;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceMovePeerStatRepository extends JpaRepository<PriceMovePeerStat, Long> {

	/**
	 * 같은 카드의 확정 집계가 그 서비스 날짜에 이미 있는지 본다 — 축이 유니크
	 * {@code UNIQUE(price_move_event_id, service_date)}(§C-9)와 정확히 같다. {@code PeerStatsBatchService}가
	 * 저장 전에 이 조회로 존재를 확인해, 같은 서비스 날짜에 배치를 두 번 돌려도 중복 저장을 시도하지 않는다.
	 */
	boolean existsByPriceMoveEventIdAndServiceDate(Long priceMoveEventId, LocalDate serviceDate);

	/**
	 * 그 카드({@code priceMoveEventId})의 <b>그 매도 체결의 서비스 날짜</b> 확정 집계 행만 본다(§C-9) — 유니크
	 * {@code UNIQUE(price_move_event_id, service_date)}와 같은 축이다. 같은 원본 거래일이 두 서비스 날짜에
	 * 재재생돼 같은 카드에 행이 둘 쌓여 있어도, 이 조회는 조회 중인 체결이 속한 서비스 날짜 행 하나만 돌려준다
	 * ({@code PostSellFeedbackReader}가 이 값으로 {@code peerComparison}을 판정한다).
	 */
	Optional<PriceMovePeerStat> findByPriceMoveEventIdAndServiceDate(Long priceMoveEventId, LocalDate serviceDate);
}
