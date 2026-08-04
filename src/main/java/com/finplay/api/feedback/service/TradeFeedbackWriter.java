// 매도 회고 서술의 저장만을 담당하는 트랜잭션 경계 전용 컴포넌트 — LLM 호출을 경계 밖에 두기 위해 분리했다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.domain.TradeFeedback;
import com.finplay.api.feedback.repository.TradeFeedbackRepository;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.TradeService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// PostSellFeedbackService는 읽기(PostSellFeedbackReader의 트랜잭션)와 서술 생성(외부 LLM 호출, 중앙값 2.5초·
// p95 3.1초 — #198 실측)을 모두 끝낸 뒤에만 이 컴포넌트를 호출한다 — 저장만 트랜잭션으로 감싸 외부 호출 대기
// 중에 DB 커넥션을 점유하지 않는다(PriceMoveCardWriter·KisHistoricalCandleImportWriter와 같은 형태·같은 이유).
// 배치와 달리 여기는 사용자가 그 시간을 기다리는 조회 경로라 커넥션 점유가 곧 동시 사용자 수의 상한이 된다.
//
// 별도 클래스인 이유는 자기호출이다 — 같은 클래스의 private 메서드에 @Transactional을 붙이면 프록시를 타지
// 않아 애노테이션이 무효가 되고, 정확히 막으려던 "LLM 대기 중 커넥션 점유" 상태가 조용히 된다.
//
// 이 spec이 회원별로 쓰는 테이블은 trade_feedbacks 하나뿐이고 그 쓰기 경로가 이 클래스뿐이다 — 8개 이슈
// 공통 조건인 "원장 불변"이 조회 경로에서 취하는 형태다. 주입된 리포지터리도 그 하나뿐이라 주문·체결·계좌·
// 잔액·보유·손익 테이블에 닿는 경로가 애초에 없다.
//
// 이슈 #208 5번(서술 재생성)이 이 클래스에 전이 메서드를 하나 더 얹는다 — 기존 행의 서술을 갈아 끼우고
// narrative_finalized·regeneration_attempts를 바꾸는 저장이며, LLM 재호출이 트랜잭션 밖이어야 하는 이유가
// 같으므로 새 경계 컴포넌트를 만들지 않는다.
@Component
@RequiredArgsConstructor
class TradeFeedbackWriter {

	private final TradeService tradeService;

	private final TradeFeedbackRepository tradeFeedbackRepository;

	/**
	 * 체결 1건의 회고 서술을 <b>처음</b> 저장한다. {@code UNIQUE(trade_id)}가 체결 1건당 1행을 강제한다.
	 *
	 * <p><b>{@code Trade}를 인자로 받지 않고 이 트랜잭션 안에서 다시 읽는다.</b> 읽기 트랜잭션이 이미 끝나
	 * 그때의 엔티티는 detached이고, 그 상태로 {@code @ManyToOne}에 걸면 Hibernate 구현 세부에 기대는 저장이 된다.
	 * {@code getOwnedTrade}를 다시 부르면 관리 상태 엔티티를 얻으면서 <b>쓰기 시점에 소유권을 한 번 더
	 * 확인</b>하게 된다 — 회원별 쓰기가 처음 생기는 자리라 남의 체결에 행이 붙는 것을 구조적으로 막는다.
	 *
	 * @param narrative {@code LLM} 아니면 {@code TEMPLATE}이다. 매도 회고는 §템플릿 문장이 있어 {@code NONE}이
	 *     되지 않으며, {@code TradeFeedback.create}가 그 전제로 서술이 비지 않음을 문서화하고 있다
	 */
	@Transactional
	TradeFeedback create(Long userId, Long tradeId, NarrativeResultDto narrative, LocalDateTime generatedAt) {
		Trade trade = tradeService.getOwnedTrade(userId, tradeId);
		return tradeFeedbackRepository.save(
			TradeFeedback.create(trade, narrative.narrative(), narrative.source(), generatedAt));
	}
}
