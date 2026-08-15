// 매도 회고 프롬프트에 실을 매수 회고 한 줄 — 줄머리 시각과 본문만 담는다.
package com.finplay.api.feedback.service;

import java.time.LocalDateTime;

/**
 * spec §LLM 프롬프트의 {@code - 매수 09:30: …} 한 줄에 대응한다 (4차, §FEED-013 결정 4·5).
 * {@link JournalDigestDto.BuyJournalLine}에서 <b>프롬프트에 필요한 부분만</b> 옮겨 담은 값이다.
 *
 * <p><b>체결 id와 지문은 담지 않는다.</b> 프롬프트 문자열에 쓰지 않는 값을 이 record에 넣으면 문자열 단정
 * 테스트가 무관한 값에 흔들린다 — 지문은 재생성 판정의 재료라 서비스가 따로 들고 있는다.
 *
 * @param buyAt   매수 체결시각. <b>포맷은 조립부의 {@code holdMoment}가 한다</b> — 하루를 넘긴 보유면 날짜가
 *     붙으며(이슈 #275), 같은 프롬프트의 매수·매도 줄과 표기가 갈리지 않아야 한다
 * @param content 본문. {@code max-journal-chars}자에서 이미 절단돼 있다({@code PostSellJournalReader})
 */
public record BuyJournalLineDto(LocalDateTime buyAt, String content) {
}
