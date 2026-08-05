// 매도 회고 서술의 저장만을 담당하는 트랜잭션 경계 전용 컴포넌트 — LLM 호출을 경계 밖에 두기 위해 분리했다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.domain.TradeFeedback;
import com.finplay.api.feedback.repository.TradeFeedbackRepository;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.TradeService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
// 재생성(§C-5)도 같은 경계를 쓴다 — LLM 재호출이 트랜잭션 밖이어야 하는 이유가 최초 생성과 같으므로 새 경계
// 컴포넌트를 만들지 않는다. 아래 세 메서드가 이 spec의 회원별 쓰기 전부다.
@Slf4j
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

	/**
	 * 재생성 <b>성공</b>을 저장한다 — 서술을 갈아 끼우고 {@code narrative_finalized}를 {@code TRUE}로 바꾼다.
	 *
	 * <p><b>행을 이 트랜잭션 안에서 다시 읽는다.</b> 호출부가 판정에 쓴 엔티티는 읽기 트랜잭션이 끝나 detached
	 * 이므로 거기에 전이 메서드를 부르면 <b>더티 체킹 대상이 아니라 아무 일도 일어나지 않는다</b> — 예외도 로그도
	 * 없이 재생성이 매 조회마다 반복되고 상한도 오르지 않는다. {@code create}가 {@code Trade}를 다시 읽는 것과
	 * 같은 이유다.
	 *
	 * @param narrative {@code LLM}이어야 한다 — 템플릿 폴백은 실패로 취급해 {@link #recordFailedRegeneration}으로
	 *     간다. 판정은 게이트를 가진 {@code PostSellFeedbackService}가 한다
	 */
	@Transactional
	void applyRegenerated(Long tradeId, NarrativeResultDto narrative, LocalDateTime generatedAt) {
		tradeFeedbackRepository.findByTradeId(tradeId).ifPresentOrElse(
			feedback -> feedback.applyRegeneratedNarrative(
				narrative.narrative(), narrative.source(), generatedAt),
			() -> log.debug("재생성 대상 회고 행이 사라져 저장을 건너뛴다. tradeId={}", tradeId));
	}

	/**
	 * 재생성 <b>실패</b>를 저장한다 — 기존 서술과 {@code narrative_finalized=false}를 유지하고
	 * {@code regeneration_attempts}만 누적한다 (FEED-007·§C-7).
	 *
	 * <p><b>이 저장을 빠뜨리면 상한이 성립하지 않는다.</b> 횟수가 오르지 않아 게이트가 계속 열려 있고, 실패하는
	 * 체결 하나가 <b>조회마다 LLM을 부르는데</b> 응답은 정상 200이라 아무 신호도 남지 않는다.
	 */
	@Transactional
	void recordFailedRegeneration(Long tradeId) {
		tradeFeedbackRepository.findByTradeId(tradeId).ifPresentOrElse(
			TradeFeedback::recordFailedRegeneration,
			() -> log.debug("재생성 대상 회고 행이 사라져 재시도 횟수 누적을 건너뛴다. tradeId={}", tradeId));
	}
}
