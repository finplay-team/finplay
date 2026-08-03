// 매도 직후 회고 서술 프롬프트의 입력 — 원장 수치와 서버가 계산한 파생 사실을 함께 담는다.
package com.finplay.api.feedback.service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/**
 * 수치는 전부 기존 원장과 서버 계산에서 온다. 모델은 이 값을 계산하지도 고치지도 않는다 (PRD C-004).
 *
 * <p><b>반사실(counterfactuals)은 이 record에 절대 넣지 않는다.</b> "안 팔았다면 -1.17%였습니다"는 사실이지만
 * "팔지 말걸"을 암시해 §후검증의 가정법 금지와 정면으로 부딪힌다. 구조화 필드로만 응답에 나가고 서술에는
 * 들어가지 않는다 (spec §왜 반사실은 AI 문장에 넣지 않는가). 필드를 추가하고 싶으면 spec을 먼저 고친다.
 *
 * <p>반대로 집단 비교는 관측된 사실이라 서술에 넣어도 된다(FEED-011). 매도 후 흐름과 집단 비교가 확정된 뒤
 * 서술을 1회 재생성하는데(§C-5), 그 둘이 프롬프트에 없으면 재생성해도 같은 문장이 나와 게이트가 무의미해진다.
 * 그래서 두 묶음을 모두 nullable로 받아 값이 있을 때만 프롬프트에 줄이 붙는다.
 *
 * @param buyAt        배분된 매수 lot 중 가장 이른 체결 시각. 이 값 하나가 아래 분(minutes) 계산의 기준이다
 * @param returnRate   비율 그대로 (-0.0217 → -2.17%)
 * @param sellVsHighRate 매도가가 보유 중 최고가 대비 얼마나 떨어져 있었는지 (음수면 그만큼 낮다)
 * @param buyToNewsMinutes 첫 근거 기사 발행시각 − 매수시각 (분). 양수면 매수가 기사보다 앞섰다.
 *                         근거 기사가 없으면 {@code null}이고 {@code firstNewsAt}도 함께 {@code null}이다
 * @param closePrice   매도 후 흐름. §C-5 게이트를 통과하기 전에는 {@code null}이며 그 줄이 프롬프트에서 빠진다
 * @param holderCount  집단 비교. 확정 집계 행이 없거나 표본이 부족하면 {@code null}이다
 */
public record PostSellPromptDto(
	String instrumentName,
	LocalTime buyAt,
	BigDecimal buyPrice,
	LocalTime sellAt,
	BigDecimal sellPrice,
	BigDecimal quantity,
	BigDecimal returnRate,
	long realizedPnl,
	BigDecimal holdHighPrice,
	LocalTime holdHighAt,
	BigDecimal sellVsHighRate,
	BigDecimal holdLowPrice,
	LocalTime holdLowAt,
	BigDecimal sellVsLowRate,
	Integer buyToNewsMinutes,
	LocalTime firstNewsAt,
	List<HeldPriceMoveDto> priceMoves,
	BigDecimal closePrice,
	BigDecimal sellToCloseRate,
	Integer holderCount,
	BigDecimal soldWithin30MinRate,
	Integer medianMinutesToSell,
	Integer yourMinutesToSell) {

	public PostSellPromptDto {
		priceMoves = List.copyOf(priceMoves);
	}
}
