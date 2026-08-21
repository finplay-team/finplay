// 매수 체결별 투자일기 영속을 담당하는 JPA 리포지터리
package com.finplay.api.domain.journal.repository;

import com.finplay.api.domain.journal.entity.BuyTradeJournal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BuyTradeJournalRepository
	extends JpaRepository<BuyTradeJournal, Long>, BuyTradeJournalRepositoryCustom {

	boolean existsByBuyTradeId(Long buyTradeId);

	Optional<BuyTradeJournal> findByBuyTradeId(Long buyTradeId);

	/**
	 * 매수 체결 id 목록으로 매수 회고를 <b>한 번에</b> 읽는다 (spec 012 §C-6, 4차 §FEED-013).
	 *
	 * <p><b>건별 조회를 N번 돌지 않는다.</b> 한 매도가 여러 lot에 배분되므로 매수 체결이 N건이 될 수 있고,
	 * 건별로 돌면 lot 수만큼 쿼리가 늘어난다.
	 *
	 * <p>매수 체결을 함께 읽는다. <b>지금 호출부만 보면 없어도 된다</b> — 2026-08-16 실측으로 지연 프록시의
	 * {@code getId()}는 추가 쿼리를 내지 않음을 확인했고(초기화는 다른 프로퍼티를 건드릴 때 일어난다), 현재
	 * 호출부는 {@code getBuyTrade().getId()}만 읽는다. 그래도 남겨 두는 것은 <b>쿼리 1회 보장을 호출부의 접근
	 * 패턴에 의존시키지 않기 위해서다</b> — 뒤에 종목명 하나만 더 읽어도 조용히 N+1이 된다.
	 * {@code BuyTradeJournalRepositoryTest}가 그 1회를 statement 수로 고정한다.
	 *
	 * <p><b>순서를 보장하지 않는다.</b> 프롬프트에 실릴 순서(매수 시각 오름차순)는 배분 조회가 정하며 호출부가
	 * 그 순서로 다시 정렬한다(§FEED-013 결정 4).
	 *
	 * @param buyTradeIds 매수 체결 id 목록. <b>빈 목록으로 호출하지 않는다</b> — 호출부가 먼저 걸러낸다
	 */
	@Query("select j from BuyTradeJournal j "
		+ "join fetch j.buyTrade buyTrade "
		+ "where buyTrade.id in :buyTradeIds")
	List<BuyTradeJournal> findAllByBuyTradeIdIn(@Param("buyTradeIds")
	Collection<Long> buyTradeIds);
}
