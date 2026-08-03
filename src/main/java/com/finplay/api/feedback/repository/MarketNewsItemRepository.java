// 수집한 뉴스·공시의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.MarketNewsItem;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드를 아직 두지 않는다. 이 이슈의 완료 조건은 {@code (instrument_id, url)} 유니크가 실제로 어떻게
 * 걸리는지이고, 그 검증에는 {@code JpaRepository}의 저장·조회로 충분하다. 수집 시 중복 판정(#3), 근거 매칭과
 * 요약·브리핑의 구간 질의(#4·#5)가 쓸 조회는 <b>각자의 이슈에서 그 이슈의 완료 조건과 함께</b> 추가한다 —
 * 지금 추측으로 만들면 시그니처가 어긋난 채 굳는다.
 */
public interface MarketNewsItemRepository extends JpaRepository<MarketNewsItem, Long> {}
