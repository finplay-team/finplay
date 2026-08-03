// 카드 ↔ 근거 기사 연결의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.PriceMoveEventSource;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드를 아직 두지 않는다. 카드별 근거 목록 조회는 응답 DTO가 생기는 이슈(#4·#6)가 그 형태에 맞춰
 * 추가한다 — 지금 추측으로 만들면 시그니처가 어긋난 채 굳는다.
 */
public interface PriceMoveEventSourceRepository extends JpaRepository<PriceMoveEventSource, Long> {}
