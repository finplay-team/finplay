// 체결 원장의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.order.repository;

import com.finplay.api.account.domain.Market;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.Trade;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TradeRepository extends JpaRepository<Trade, Long>, TradeRepositoryCustom {

	Optional<Trade> findByOrderId(Long orderId);

	@Override
	@EntityGraph(attributePaths = {"account", "account.user"})
	Optional<Trade> findById(Long id);

	// 랭킹 재구성 대상 조회(이슈 #279, #366) — 해당 시장 계좌 중 실제 종목 매도 체결 이력이 있는 계좌 id를
	// 중복 없이 가져온다. 판정 기준은 반드시 trades.side = 'SELL'이다. accounts.realized_pnl != 0을 기준으로
	// 쓰면 매도했지만 손익이 정확히 0인 계좌가 누락돼 재구성 결과가 유실 전과 달라진다(spec.md 비즈니스 규칙).
	// 샌드박스 종목(instruments.is_tutorial_sample=true) 매도만 있는 계좌는 대상에서 제외한다(033 spec).
	// DISTINCT가 필요해 파생 쿼리 대신 @Query로 쓴다(단일 필드 프로젝션은 파생 쿼리 이름으로 되지 않는다).
	@Query("SELECT DISTINCT t.account.id FROM Trade t WHERE t.side = :side AND t.account.market = :market "
		+ "AND t.instrument.tutorialSample = false")
	List<Long> findDistinctAccountIdsBySideAndMarket(
		@Param("side")
		OrderSide side,
		@Param("market")
		Market market);

	// 내 랭킹 status 판정(이슈 #279, #366) — 이 계좌에 실제 종목 매도 이력이 있는가(단건, account_id 인덱스
	// 조회). 샌드박스 종목만 매도한 계좌는 매도 이력 없는 계좌와 동일하게 취급한다(033 spec).
	boolean existsByAccountIdAndSideAndInstrument_TutorialSampleFalse(Long accountId, OrderSide side);

	// 랭킹 목록 status 판정(이슈 #279, #366) — 이 시장에 실제 종목 매도 이력 계좌가 하나라도 있는가
	// (account.market 중첩 탐색). 샌드박스 종목만 매도한 계좌는 대상에서 제외한다(033 spec).
	boolean existsBySideAndAccountMarketAndInstrument_TutorialSampleFalse(OrderSide side, Market market);

	// 026-market-order-practice-tutorial 2단계 chain 해석용 — 사용자·종목의 intention.createdAt 이후 체결된
	// BUY 체결을 오래된 순으로 가져온다(Trade는 체결 결과만 영속하므로 이 조회 결과 자체가 FILLED 체결이다).
	// 수량 정규화 비교(BigDecimal.compareTo)는 DB 조건이 아니라 호출부(TradeService)에서 수행한다.
	List<Trade> findByAccount_User_IdAndInstrument_IdAndSideAndExecutedAtAfterOrderByExecutedAtAscIdAsc(
		Long userId, Long instrumentId, OrderSide side, LocalDateTime after);

	// 030 holding 관찰 세션 가격원 역추적용(이슈 #321) — buyTrade가 귀속된 order의 practicePriceSessionId만
	// 프로젝션한다. 단일 필드 프로젝션은 파생 쿼리 이름으로 되지 않아 @Query로 쓴다(agent-mistakes.md 2026-08-03).
	@Query("SELECT t.order.practicePriceSessionId FROM Trade t WHERE t.id = :tradeId")
	Optional<Long> findPracticePriceSessionIdByTradeId(@Param("tradeId")
	Long tradeId);

	// 재시작 보상 수량은 수정 가능한 holding이 아니라 현재 attempt/run의 불변 체결 원장에서 계산한다.
	@Query("""
		select t from Trade t
		where t.order.practiceAttemptId = :attemptId
		  and t.order.practiceAttemptRunNumber = :runNumber
		  and t.order.status = com.finplay.api.order.domain.OrderStatus.FILLED
		order by t.id asc
		""")
	List<Trade> findFilledPracticeRunTrades(
		@Param("attemptId")
		Long attemptId,
		@Param("runNumber")
		long runNumber);

	// 041 대기 구간 탈출 시 delta를 자를 기준 시각 — 남은 delta를 전부 이월하면 사용자가 매수 직후 처음 보는
	// 화면이 진행 구간 한참 뒤가 되어 가격이 튄다(041 plan §상태 전이표). 순회 중 체결도 그 시점 시각으로
	// 기록되므로 이 한 값이 두 경우를 모두 덮는다.
	@Query("""
		select max(t.executedAt) from Trade t
		where t.order.practiceAttemptId = :attemptId
		  and t.order.practiceAttemptRunNumber = :runNumber
		  and t.order.status = com.finplay.api.order.domain.OrderStatus.FILLED
		  and t.side = com.finplay.api.order.domain.OrderSide.BUY
		""")
	Optional<java.time.LocalDateTime> findLatestPracticeRunBuyExecutedAt(
		@Param("attemptId")
		Long attemptId,
		@Param("runNumber")
		long runNumber);
}
