// 카드 ↔ 근거 기사 연결의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.PriceMoveEventSource;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 조회 메서드는 <b>필요해진 이슈에서 하나씩</b> 더한다. 매도 회고의 보유 구간 카드 근거({@code plan.md} 6번)는
 * 그 이슈가 그 형태에 맞춰 추가한다 — 지금 추측으로 만들면 시그니처가 어긋난 채 굳는다.
 */
public interface PriceMoveEventSourceRepository extends JpaRepository<PriceMoveEventSource, Long> {

	/**
	 * 여러 카드의 근거를 <b>한 번에</b> 조회한다. 카드마다 따로 물으면 목록 응답에서 카드 수만큼 왕복이 생긴다.
	 *
	 * <p>{@code JOIN FETCH}로 기사를 함께 읽는다 — 연관이 {@code LAZY}라 이것이 없으면 응답을 조립하며
	 * 근거 수만큼 추가 질의가 나간다(N+1). <b>빈 컬렉션을 넘기지 않는다</b>: {@code IN ()}은 유효한 SQL이 아니라
	 * 호출부가 카드 0건이면 이 메서드를 부르지 않는다.
	 *
	 * <p>정렬을 <b>발행시각 내림차순</b>으로 고정한다. 연결 테이블에는 순서 컬럼이 없어 정렬을 주지 않으면
	 * 화면의 근거 순서가 실행마다 달라진다. 같은 시각이면 저장 순서({@code id})로 가른다.
	 */
	@Query("SELECT s FROM PriceMoveEventSource s JOIN FETCH s.marketNewsItem n "
		+ "WHERE s.priceMoveEvent.id IN :priceMoveEventIds "
		+ "ORDER BY n.publishedAt DESC, s.id ASC")
	List<PriceMoveEventSource> findAllByPriceMoveEventIdIn(@Param("priceMoveEventIds")
	Collection<Long> priceMoveEventIds);
}
