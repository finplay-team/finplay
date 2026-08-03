// 매도 회고 서술의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.TradeFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드를 아직 두지 않는다 — 앞의 리포지터리들과 같은 이유다. 체결 1건의 기존 서술 조회와 재생성 게이트
 * 판정은 조회 서비스(#6)가 자기 완료 조건과 함께 추가한다. 이 이슈가 보는 것은 {@code (trade_id)} 유니크가
 * 체결 1건당 1행을 실제로 강제하는지까지다.
 */
public interface TradeFeedbackRepository extends JpaRepository<TradeFeedback, Long> {}
