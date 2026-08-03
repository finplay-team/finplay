// 시장·거래일 단위 개장 전 브리핑의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.MarketBriefing;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드를 아직 두지 않는다. 브리핑 조회는 {@code items}를 함께 질의해 상태값을 판정해야 하고(§C-4) 그
 * 형태가 응답 DTO와 함께 정해지므로, 질의는 #5에서 완료 조건과 함께 추가한다.
 */
public interface MarketBriefingRepository extends JpaRepository<MarketBriefing, Long> {}
