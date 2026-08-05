// 매도 회고 서술의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.TradeFeedback;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드는 <b>필요해진 이슈에서 하나씩</b> 더한다 — 앞의 리포지터리들과 같은 이유다.
 */
public interface TradeFeedbackRepository extends JpaRepository<TradeFeedback, Long> {

	/**
	 * 체결 1건의 기존 서술이다. <b>최초 조회에서 생성해 저장하고 이후에는 재사용한다</b>(FEED-007) — 이 조회가
	 * 비어 있는지가 곧 "LLM을 부를지"의 판정이므로, 빠뜨리면 <b>조회마다 서술을 다시 만들어</b> 호출량이 조회 수에
	 * 비례하고 같은 체결의 문장이 매번 달라진다.
	 *
	 * <p>{@code UNIQUE(trade_id)}가 체결 1건당 1행을 강제하므로 {@code Optional}이 정확한 반환이다
	 * (§데이터 모델). 재생성(이슈 #208 5번)도 이 행을 찾아 갈아 끼운다.
	 *
	 * <p><b>{@code Trade}를 함께 읽지 않는다.</b> 연관이 {@code LAZY}이고 호출부는 서술 두 값과 재생성 상태만
	 * 보므로 {@code JOIN FETCH}가 필요 없다 — 원장 수치는 이미 다른 경로가 읽어 둔 상태다.
	 */
	Optional<TradeFeedback> findByTradeId(Long tradeId);
}
