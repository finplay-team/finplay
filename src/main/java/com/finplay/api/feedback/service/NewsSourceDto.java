// 프롬프트에 넣을 근거 기사·공시 1건 — 제목·언론사·발행시각만 담고 본문은 담지 않는다.
package com.finplay.api.feedback.service;

import java.time.LocalDateTime;

/**
 * 저작권 때문에 본문·요약 스니펫을 담지 않는다 (spec §정책 전제). 화면 노출 항목과 같은 네 값 중
 * 원문 URL만 뺀 셋이다 — 모델에게 URL을 주면 인용하려 들 뿐 서술에 쓸모가 없다.
 *
 * <p>{@code disclosure}가 {@code true}면 OpenDART 공시다. 공시는 접수일자만 있어 {@code publishedAt}의
 * 시각 부분이 항상 {@code 00:00:00}이므로(spec §C-3) 프롬프트에도 시각 대신 "접수"로 적는다.
 * 엔티티의 {@code type} 열거형은 #2 소유라 여기서 정의하지 않고 boolean으로 받는다.
 */
public record NewsSourceDto(
	String title,
	String publisher,
	LocalDateTime publishedAt,
	boolean disclosure) {
}
