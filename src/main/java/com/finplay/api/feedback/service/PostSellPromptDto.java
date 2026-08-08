// 매도 직후 회고 서술 프롬프트의 입력 — 원장 수치와 서버가 계산한 파생 사실을 함께 담는다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.domain.HoldHighBasis;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * <p><b>시각을 {@code LocalTime}이 아니라 {@code LocalDateTime}으로 받는다</b>(이슈 #275). 코인은 §FEED-012
 * 결정 4의 {@link HoldHighBasis#DAILY} 경로가 <b>정의상 200분 초과 보유</b>라 대부분 날짜를 넘기는데, 시각을
 * 시분으로 좁혀 넘기면 8/1 14:20 매수 → 8/5 09:05 매도가 모델에게 "매수 14:20 / 매도 09:05"로만 보여
 * <b>매도가 매수보다 이르다는 문장</b>이 나온다. 날짜를 문장에 실제로 쓸지는 {@code multiDayHold}가 정한다.
 *
 * @param buyAt        배분된 매수 lot 중 가장 이른 체결 시각. 이 값 하나가 아래 분(minutes) 계산의 기준이다
 * @param returnRate   비율 그대로 (-0.0217 → -2.17%)
 * @param holdHighPrice 보유 구간 극값. <b>{@code holdHighPrice}·{@code holdHighAt}·{@code sellVsHighRate}가
 *                      한 묶음, {@code holdLowPrice}·{@code holdLowAt}·{@code sellVsLowRate}가 다른 한 묶음이며
 *                      묶음 단위로 함께 {@code null}이다.</b> {@code sameSessionCompleted=false}(배분된 lot이
 *                      여러 원본 거래일에 걸친 매매)면 여섯 개가 전부 {@code null}이다 — 분봉이 불연속이라
 *                      계산이 성립하지 않는다 (spec §파생 사실 계산, api-contracts 매도 직후 피드백).
 *                      프롬프트와 템플릿 문장 모두 그때는 해당 줄·문장을 통째로 생략한다
 * @param sellVsHighRate 매도가가 보유 중 최고가 대비 얼마나 떨어져 있었는지 (음수면 그만큼 낮다)
 * @param buyToNewsMinutes 첫 근거 기사 발행시각 − 매수시각 (분). 양수면 매수가 기사보다 앞섰다.
 *                         근거 기사가 없으면 {@code null}이고 {@code firstNewsAt}도 함께 {@code null}이다
 * @param closePrice   매도 후 흐름. §C-5 게이트를 통과하기 전에는 {@code null}이며 그 줄이 프롬프트에서 빠진다
 * @param holderCount  집단 비교. 확정 집계 행이 없거나 표본이 부족하면 {@code null}이다
 * @param multiDayHold 보유가 하루를 넘겼는가 — 참이면 위 네 시각을 날짜까지 서술한다. 호출부가
 *                     {@code sameSessionCompleted && 매수일 != 매도일}로 정한다. <b>주식은 언제나 거짓이다</b>
 *                     ({@code sameSessionCompleted=true}가 곧 같은 원본 거래일이라는 뜻이므로) — 그래서 주식
 *                     문장은 이 필드가 생기기 전과 한 글자도 달라지지 않는다
 * @param holdHighBasis 극값을 어느 표본으로 쟀는가. {@link HoldHighBasis#DAILY}면 극값 시각의 시·분
 *                      ({@code 23:59})이 <b>일봉 라벨일 뿐 실제로 잰 시각이 아니므로</b> 문장이 시각 대신
 *                      "그 일자의 종가"라고 적는다. 주식은 언제나 {@link HoldHighBasis#MINUTE}다
 */
public record PostSellPromptDto(
	String instrumentName,
	LocalDateTime buyAt,
	BigDecimal buyPrice,
	LocalDateTime sellAt,
	BigDecimal sellPrice,
	BigDecimal quantity,
	BigDecimal returnRate,
	long realizedPnl,
	BigDecimal holdHighPrice,
	LocalDateTime holdHighAt,
	BigDecimal sellVsHighRate,
	BigDecimal holdLowPrice,
	LocalDateTime holdLowAt,
	BigDecimal sellVsLowRate,
	Integer buyToNewsMinutes,
	LocalDateTime firstNewsAt,
	List<HeldPriceMoveDto> priceMoves,
	BigDecimal closePrice,
	BigDecimal sellToCloseRate,
	Integer holderCount,
	BigDecimal soldWithin30MinRate,
	Integer medianMinutesToSell,
	Integer yourMinutesToSell,
	boolean multiDayHold,
	HoldHighBasis holdHighBasis) {

	public PostSellPromptDto {
		priceMoves = List.copyOf(priceMoves);
	}
}
