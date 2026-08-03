// 카드별 집단 행동 집계의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.PriceMovePeerStat;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드를 아직 두지 않는다. 집계 저장은 장 마감 배치(#7), 조회는 "그 체결의 서비스 날짜 행"만 보는
 * 질의(#7)라 둘 다 그 이슈에서 완료 조건과 함께 추가한다 — 지금 추측으로 만들면 시그니처가 어긋난 채 굳는다.
 */
public interface PriceMovePeerStatRepository extends JpaRepository<PriceMovePeerStat, Long> {}
