// 투자일기 1건의 본문과 최종 수정 시각을 도메인 밖으로 내보내는 읽기 전용 내부 DTO
package com.finplay.api.journal.service;

import java.time.LocalDateTime;

/**
 * spec 012 §C-6이 {@code journal}에 요구한 조회 경로의 반환 타입이다 — {@code feedback}의 매도 회고
 * (§FEED-013, 4차)가 프롬프트 입력과 지문 계산에 쓴다.
 *
 * <p><b>엔티티를 도메인 밖으로 내보내지 않기 위한 자리다.</b> {@code buy_trade_journals}·
 * {@code sell_trade_journals}는 {@code journal} 소유라 다른 도메인이 repository를 직접 주입하지 않고 서비스를
 * 경유한다({@code docs/conventions.md}, ADR-0002).
 *
 * <p><b>본문을 절단하지 않는다.</b> 절단 상한이 {@code feedback.journal.*} 설정이라 절단은 {@code feedback}의
 * 책임이다({@code PostSellJournalReader}). 여기서는 저장된 본문을 그대로 준다.
 *
 * @param tradeId   이 일기가 달린 체결 id (매수 회고면 매수 체결, 매도 회고면 매도 체결)
 * @param content   일기 본문 (저장된 원문. 최대 5000자)
 * @param updatedAt 최종 수정 시각. 수정 때마다 갱신되므로(JOUR-002·004) 지문이 이 값으로 본문 변경을 따라온다
 *                  (§FEED-013 결정 3)
 */
public record JournalContentDto(Long tradeId, String content, LocalDateTime updatedAt) {
}
