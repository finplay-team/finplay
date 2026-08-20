// 매도 직후 피드백 조회 응답 — 원장 수치와 서버가 계산한 파생 사실·반사실·집단 비교·관찰형 서술을 담는다.
package com.finplay.api.feedback.dto.response;

import com.finplay.api.feedback.domain.HoldHighBasis;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.PostSellFeedbackStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 계약은 {@code docs/api/feedback.md}의 "매도 직후 피드백 조회" 소절이 정본이고, 요구사항은 spec FEED-007·
 * 010·011·012다. <b>주식·코인이 같은 record를 쓴다</b> — 3차(이슈 #275)가 코인을 열면서도 키 집합을 가르지
 * 않았다(§FEED-012 "응답 계약이 시장별로 달라지는가"). 갈리는 것은 값뿐이고 조립 주체가
 * {@code PostSellFeedbackReader}({@code STOCK}) / {@code CryptoPostSellFeedbackReader}({@code CRYPTO})로 나뉜다.
 *
 * <p><b>{@code sameSessionCompleted}가 응답 형태를 가른다.</b> 매수와 매도가 같은 원본 거래일 안에서 완결됐으면
 * {@code true}이고 극값·파생 사실·카드·매도 후 흐름·반사실·집단 비교가 채워진다. 여러 재생일에 걸친 매매는
 * {@code false}이며 그 필드가 전부 {@code null}({@code priceMoves}는 {@code []})이다 — 분봉이 불연속이라 계산
 * 자체가 성립하지 않는다. <b>한 매도가 여러 lot에 배분됐고 그 lot들이 서로 다른 원본 거래일에 걸쳐 있어도
 * {@code false}다</b> — 가장 이른 lot 하나만 보고 판정하지 않는다. <b>코인은 항상 {@code true}다</b>(§FEED-012
 * 결정 0) — 재생일이 없어 시간축이 언제나 연속이다.
 *
 * <p><b>정적 팩토리를 두지 않는다.</b> 단일 엔티티 매핑이 아니라 원장 체결·FIFO 배분·분봉·카드·집계·서술
 * 여섯 곳에서 모인 값이고({@code docs/conventions/code.md}의 "엔티티 매핑이 없는 단순 DTO는 생략"), 항목마다
 * 인자를 늘리는 팩토리를 두면 조립 지점이 둘로 갈린다. 조립은 {@code PostSellFeedbackService} 한 곳이다.
 *
 * <p><b>필드를 나중에 더하지 않는다.</b> 계약이 정한 필드 집합은 처음부터 전부 선언하고 아직 채우지 않는 값은
 * {@code null}·{@code []}로 둔다 — 필드를 뒤 항목에서 더하면 그 사이 계약이 깨진 상태로 머지된다. 투자일기의
 * 구조화 필드({@code plan}·{@code planOutcome})는 생기면 <b>추가</b>하면 되므로 계약이 깨지지 않는다.
 *
 * @param buyAt        <b>배분된 매수 lot 중 가장 이른 {@code executed_at}</b>이다(FEED-007). 한 매도가 여러 lot에
 *                     배분되므로 "매수 시각"이 단일하지 않고, 이 값 하나가 {@code holdingMinutes}·
 *                     {@code buyToNewsMinutes}·{@code minutesAfterBuy}·보유 구간 극값·반사실의 기준을 전부
 *                     결정한다. <b>날짜는 그 체결의 원본 거래일이다</b> — 조회한 날짜도, 서비스 날짜도 아니다
 * @param sellAt       매도 체결 시각. {@code buyAt}과 같은 축(원본 거래일)이다
 * @param buyPrice     {@code trade_allocations}의 FIFO 배분 가중평균 매수단가(배분 원가 합 ÷ 배분 수량 합).
 *                     <b>재계산하거나 LLM에게 계산시키지 않는다</b>(PRD C-004)
 * @param returnRate   {@code realizedPnl ÷ (배분된 매수원가 합 + 배분된 매수수수료 합)}, scale 4
 *                     {@code HALF_UP}. 계약이 정한 식 그대로다
 * @param holdingMinutes {@code sellAt − buyAt} (분)
 * @param priceMoves   보유 구간에 걸친 변동 카드. {@code windowStart} 오름차순 + {@code id} 오름차순이고 각
 *                     카드의 {@code sources}는 {@code publishedAt} 내림차순이다(계약). 카드에도 §C-5의 노출
 *                     게이트가 걸린다 — 근거 기사가 {@code windowEnd} 이후에 발행될 수 있어 게이트를 빼면
 *                     Part A·C보다 먼저 그 기사를 보게 된다
 * @param narrativeStatus <b>항상 {@code READY}다</b> — 템플릿 문장이 있어 LLM이 실패해도 서술이 비지 않으므로
 *                     이 엔드포인트에 {@code UNAVAILABLE}이 <b>존재하지 않는다</b>(§C-4). 어느 쪽으로
 *                     만들어졌는지는 {@code narrativeSource}로 구분한다
 */
public record PostSellFeedbackResponse(
	Long tradeId,
	Long instrumentId,
	String symbol,
	String name,
	LocalDateTime buyAt,
	LocalDateTime sellAt,
	BigDecimal buyPrice,
	BigDecimal sellPrice,
	BigDecimal quantity,
	long fee,
	Long realizedPnl,
	BigDecimal returnRate,
	Integer holdingMinutes,
	boolean sameSessionCompleted,
	BigDecimal holdHighPrice,
	LocalDateTime holdHighAt,
	BigDecimal holdLowPrice,
	LocalDateTime holdLowAt,
	BigDecimal sellVsHighRate,
	BigDecimal sellVsLowRate,
	HoldHighBasis holdHighBasis,
	Integer buyToNewsMinutes,
	List<HeldPriceMoveItem> priceMoves,
	PostSellFlow postSellFlow,
	Counterfactuals counterfactuals,
	PeerComparison peerComparison,
	String narrative,
	NarrativeSource narrativeSource,
	PostSellFeedbackStatus narrativeStatus) {

	// 컬렉션 필드를 가진 record는 방어적 복사가 없으면 spotbugsMain이 EI_EXPOSE_REP으로 잡는다
	// (ai/agent-mistakes.md 2026-07-29).
	public PostSellFeedbackResponse {
		priceMoves = List.copyOf(priceMoves);
	}

	/**
	 * 서술 세 필드만 채운 사본이다. 나머지 25개는 그대로다.
	 *
	 * <p><b>정적 팩토리를 두지 않는다는 위 방침과 어긋나지 않는다</b> — 엔티티를 응답으로 옮기는 매핑이 아니라
	 * <b>같은 조립의 마지막 한 걸음</b>이다. 이 메서드가 필요한 이유는 트랜잭션 경계다: 원장 수치·파생 사실은
	 * 읽기 트랜잭션 안에서 조립되고 서술은 <b>그 트랜잭션이 끝난 뒤</b> 외부 LLM 호출로 만들어지므로
	 * (spec FEED-007 — 조회 경로에 LLM이 들어오는 유일한 자리), 한 번에 28개를 채울 수 있는 지점이 없다.
	 * 조립 지점을 둘로 쪼개는 대신 <b>여기 한 곳에서 사본을 만든다</b> — 호출부가 25개를 다시 나열하면
	 * 필드가 늘 때마다 그 목록이 조용히 뒤처진다.
	 *
	 * @param narrativeStatus <b>항상 {@code READY}다</b>(§C-4). 인자로 받는 것은 열거형 값을 이 record가 아는
	 *     자리를 늘리지 않으려는 것뿐이고, {@code UNAVAILABLE}은 이 엔드포인트에 존재하지 않는다
	 */
	public PostSellFeedbackResponse withNarrative(
		String narrative, NarrativeSource narrativeSource, PostSellFeedbackStatus narrativeStatus) {
		return new PostSellFeedbackResponse(
			tradeId,
			instrumentId,
			symbol,
			name,
			buyAt,
			sellAt,
			buyPrice,
			sellPrice,
			quantity,
			fee,
			realizedPnl,
			returnRate,
			holdingMinutes,
			sameSessionCompleted,
			holdHighPrice,
			holdHighAt,
			holdLowPrice,
			holdLowAt,
			sellVsHighRate,
			sellVsLowRate,
			holdHighBasis,
			buyToNewsMinutes,
			priceMoves,
			postSellFlow,
			counterfactuals,
			peerComparison,
			narrative,
			narrativeSource,
			narrativeStatus);
	}
}
