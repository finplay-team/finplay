// 변동 구간 카드의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.PriceMoveEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드를 아직 두지 않는다 — {@code MarketNewsItemRepository}와 같은 이유다. 이 이슈가 검증하는 것은
 * 주식·코인 두 형태의 매핑과 유니크뿐이고 그 단정에는 저장·조회로 충분하다. 노출 게이트 질의(#4), 보유 구간
 * 카드 조회(#6), 코인 쿨다운·일일 상한 카운트(#8)는 <b>각자의 이슈에서 그 이슈의 완료 조건과 함께</b> 추가한다.
 */
public interface PriceMoveEventRepository extends JpaRepository<PriceMoveEvent, Long> {}
