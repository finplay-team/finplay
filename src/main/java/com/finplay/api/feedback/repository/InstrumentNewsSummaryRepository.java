// 종목·거래일·범위 단위 뉴스 요약의 영속을 담당하는 JPA 리포지터리
package com.finplay.api.feedback.repository;

import com.finplay.api.feedback.domain.InstrumentNewsSummary;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 조회 메서드를 아직 두지 않는다 — 앞의 두 리포지터리와 같은 이유다. 배치의 UPSERT·재생성 판정(#5)과 조회
 * 경로(주식은 {@code (종목, 거래일, scope)}, 코인은 {@code generated_at} 최신 1행)가 쓸 질의는 <b>그 이슈에서
 * 완료 조건과 함께</b> 추가한다. 이 이슈가 보는 것은 유니크가 그 UPSERT를 성립시키는지까지다.
 */
public interface InstrumentNewsSummaryRepository extends JpaRepository<InstrumentNewsSummary, Long> {}
