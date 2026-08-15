// 매도 회고 프롬프트에 실을 투자일기 묶음과 그 지문 — 조회·정렬·절단이 끝난 조립 중간값이다.
package com.finplay.api.feedback.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 규칙의 정본은 spec §FEED-013 결정 3·4다(4차). {@link PostSellJournalReader}가 채운다.
 *
 * <p><b>응답 DTO가 아니라 조립 중간값이라 {@code dto/response/}에 두지 않는다</b> — {@link HoldExtremes}와 같은
 * 자리이며 §C-6이 이 이름을 {@code service/}에 둔 이유다. <b>일기 본문은 응답으로 나가지 않는다</b>(§범위 제외
 * — 프론트는 이미 {@code GET /api/journal/...}로 읽는다).
 *
 * <p><b>여기 담긴 것이 곧 프롬프트에 실리는 것이고, 지문도 그 범위로만 계산돼 있다</b>(결정 3). 상한
 * ({@code max-buy-journals})에 걸려 빠진 일기는 목록에도 지문에도 없다 — 넣으면 출력이 달라질 수 없는 수정에도
 * 재생성이 돌아 카운터만 소모한다.
 *
 * @param sellJournalContent 매도 회고 본문(절단 완료). 없으면 {@code null}이다 — 체결당 1건이 스키마로 강제된다
 *     ({@code UNIQUE(sell_trade_id)})
 * @param buyJournals        매수 회고. <b>매수 시각 오름차순</b>이고 상한만큼만 담긴다(결정 4). 일기가 없는 매수
 *     체결은 아예 빠지므로 배분된 lot 수와 이 크기는 다르다
 * @param fingerprint        실린 일기의 {@code (종류, 체결 ID, updated_at)}에서 계산한 SHA-256 hex 64자.
 *     <b>일기가 하나도 없으면 {@code null}이며 그것이 {@code journal_fingerprint} 컬럼이
 *     {@code VARCHAR(64) NULL}인 이유다</b> — {@code null}에서 값으로 바뀌는 것도 "달라짐"이고, 그 경로가 결정 1의
 *     "나중에 일기를 쓰는" 사용자다
 */
record JournalDigestDto(String sellJournalContent, List<BuyJournalLine> buyJournals, String fingerprint) {

	JournalDigestDto {
		// 컬렉션 필드를 가진 record는 방어적 복사가 없으면 spotbugsMain이 EI_EXPOSE_REP으로 잡는다
		// (docs/agent-mistakes.md 2026-07-29).
		buyJournals = List.copyOf(buyJournals);
	}

	/** 일기가 하나도 없을 때. 지문이 {@code null}인 유일한 경우다. */
	static JournalDigestDto empty() {
		return new JournalDigestDto(null, List.of(), null);
	}

	/**
	 * 프롬프트에 실을 일기가 하나도 없는가. 참이면 프롬프트가 3차와 <b>한 글자도 다르지 않아야 한다</b>(결정 1).
	 *
	 * <p><b>운영 코드에는 호출부가 없고 테스트 단정에만 쓴다</b>(2026-08-16 리뷰 지적으로 정정 — 그전까지 이
	 * 주석은 "호출부가 일기 덩어리를 빼는 판정에 쓴다"고 적혀 있었으나 사실이 아니었다). 같은 판정이 실제로
	 * 놓이는 자리는 둘이며 <b>둘 다 이 메서드를 부를 수 없다</b> — {@code PostSellJournalReader}는 이 record를
	 * 만들기 <b>전에</b> 조회 결과로 판정하고, {@code NarrativePromptBuilder}는 이 타입이 아니라
	 * {@code PostSellPromptDto}를 받는다(프롬프트 조립부는 지문을 알 필요가 없어 그 자리에 지문 없는 타입을
	 * 둔 것이 §C-6의 의도다).
	 *
	 * <p>그래서 규칙이 세 곳에 있는 셈이고 <b>한 곳만 고치면 갈린다.</b> 셋을 한 타입으로 모으려면 조립부가
	 * 지문까지 받아야 해서 그 의도가 깨지므로, 합치는 대신 이 관계를 여기 적어 둔다.
	 */
	boolean isEmpty() {
		return sellJournalContent == null && buyJournals.isEmpty();
	}

	/**
	 * 매수 회고 1건.
	 *
	 * @param buyTradeId 이 일기가 달린 매수 체결 id
	 * @param buyAt      그 매수의 체결시각 — 프롬프트 줄머리({@code - 매수 09:30:})의 시각이다.
	 *     <b>포맷하지 않은 {@code LocalDateTime} 그대로다</b>: 시·분만 적을지 날짜까지 적을지는 기존
	 *     {@code multiDayHold} 규칙이 정하고(§FEED-013, 이슈 #275) 그 판단 재료는 프롬프트 조립부에 있다.
	 *     <b>가장 이른 매수 시각({@code buyAt} 파생 사실)으로 대신하면 lot이 둘 이상일 때 틀린 시각이 조용히
	 *     실린다</b>
	 * @param content    본문. {@code max-journal-chars}자에서 이미 절단돼 있다
	 */
	record BuyJournalLine(Long buyTradeId, LocalDateTime buyAt, String content) {
	}
}
