// 매도 회고의 주식·코인 경로가 함께 쓰는 순수 계산 — 수익률·비율·수수료·분 단위 차이의 단일 출처.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.domain.PostSellFeedbackStatus;
import com.finplay.api.feedback.domain.PriceMovePeerStat;
import com.finplay.api.feedback.dto.response.PeerComparison;
import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * 계약은 {@code docs/api/feedback.md}의 "매도 직후 피드백 조회" 소절이고 식은 spec §파생 사실 계산·
 * §반사실·집단 비교 계산이다.
 *
 * <p><b>왜 따로 있는가.</b> 3차(이슈 #275)가 코인 경로를 열면서 조립 주체가
 * {@link PostSellFeedbackReader}(주식)와 {@link CryptoPostSellFeedbackReader}(코인) 둘로 갈렸다. 두 경로가
 * 다른 것은 <b>시간축·가격 소스·게이트</b>이고 <b>산술은 같다</b> — 그런데 산술을 각자 갖게 두면 특히
 * {@link #counterfactualReturnRate}가 위험하다. spec이 그 식을 "{@code OrderExecutionService}와 같은 식이어야
 * 한다"로 못박아 두었는데, 두 곳에 복제되면 <b>한쪽만 고쳐도 예외 없이 다른 수익률이 나간다.</b>
 *
 * <p><b>여기에는 시간축이 들어오지 않는다.</b> 인자로 받는 {@code LocalDateTime}은 이미 호출부가 자기 축으로
 * 맞춘 값이다 — 주식은 원본 거래일 축, 코인은 실제 시각이다. 이 클래스가 날짜를 만들거나 옮기면 그 구분이
 * 여기로 새어 들어와 두 경로가 다시 얽힌다.
 */
final class PostSellArithmetic {

	// 계약이 정한 수익률 scale·라운딩. HoldingValuationService와 같은 값이다.
	static final int RETURN_RATE_SCALE = 4;

	// 파생 사실 비율(sellVsHighRate·sellVsLowRate·sellToCloseRate)의 scale. 계약 예시(-0.0325·0.0059)가 소수
	// 4자리다. returnRate와 값은 같지만 근거가 다르다 — 그쪽은 계약이 식과 함께 못박은 값이고 이쪽은 §파생 사실
	// 계산의 뺄셈·나눗셈이라, 한쪽 정밀도를 바꿀 이유가 생겼을 때 다른 쪽이 딸려 가지 않게 따로 둔다.
	static final int DERIVED_RATE_SCALE = 4;

	// 집단 비교 soldWithin30MinRate의 scale — 위 두 상수와 같은 이유로 독립해 둔다. §반사실·집단 비교 계산은
	// 이 비율의 scale을 못박지 않아 나머지 파생 비율(scale 4 HALF_UP)과 같은 정밀도를 따른다.
	static final int PEER_RATE_SCALE = 4;

	// 반사실 시나리오의 매도수수료율 — OrderExecutionService의 시장별 요율과 같은 값이다(§반사실·집단 비교
	// 계산). 그쪽 필드는 private이고 주문 실행이라는 다른 트랜잭션 경계에 있어 상수를 공개해 의존을 만들지 않고
	// 값만 재사용한다. 3차(이슈 #275)에서 코인이 열리며 두 값이 다 쓰인다 — 코인에 주식 요율을 쓰면 반사실
	// 수익률이 예외도 로그도 없이 어긋난다.
	private static final BigDecimal STOCK_FEE_RATE = new BigDecimal("0.00015");

	private static final BigDecimal CRYPTO_FEE_RATE = new BigDecimal("0.0005");

	// 집단 비교 모집단 지표를 노출하는 최소 표본. 미만이면 INSUFFICIENT_SAMPLE이다(§C-4).
	private static final int MIN_PEER_SAMPLE = 5;

	private PostSellArithmetic() {}

	/** 시장별 매도수수료율. 반사실 시나리오가 가격마다 수수료를 다시 계산할 때 쓴다. */
	static BigDecimal feeRateOf(Market market) {
		return market == Market.CRYPTO ? CRYPTO_FEE_RATE : STOCK_FEE_RATE;
	}

	/**
	 * {@code realizedPnl ÷ (배분된 매수원가 합 + 배분된 매수수수료 합)}. 계약이 정한 식·scale·라운딩 그대로다.
	 *
	 * <p>분모가 0이거나 실현손익이 없으면 {@code ZERO}로 둔다 — 그런 체결은 원장에 생기지 않지만
	 * {@code ArithmeticException}으로 조회 전체가 500이 되는 것보다 낫다.
	 */
	static BigDecimal returnRate(Long realizedPnl, long buyBasis) {
		if (buyBasis == 0L || realizedPnl == null) {
			return BigDecimal.ZERO;
		}
		return BigDecimal.valueOf(realizedPnl)
			.divide(BigDecimal.valueOf(buyBasis), RETURN_RATE_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * {@code (price − basePrice) ÷ basePrice}, scale 4 {@code HALF_UP} (§파생 사실 계산).
	 *
	 * <p>세 곳이 쓴다 — {@code sellVsHighRate}·{@code sellVsLowRate}는 {@code (매도가 − 극값)}이고
	 * {@code sellToCloseRate}는 {@code (종가 − 매도가)}로 <b>기준가 자리가 뒤바뀐다.</b> 인자 순서를 헷갈리면
	 * 부호만 반대인 값이 나가는데 예외도 로그도 없으므로, 호출부마다 어느 쪽이 기준인지 주석으로 남긴다.
	 *
	 * @return 기준가가 0이면 {@code null} — 원장에 없는 값이지만 {@code ArithmeticException}으로 조회 전체가
	 *     500이 되는 것보다 낫다
	 */
	static BigDecimal rateAgainst(BigDecimal price, BigDecimal basePrice) {
		if (price == null || basePrice == null || basePrice.signum() == 0) {
			return null;
		}
		return price.subtract(basePrice).divide(basePrice, DERIVED_RATE_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * 반사실 시나리오 가격 {@code price}로 팔았다면의 수익률 (§반사실·집단 비교 계산).
	 *
	 * <p><b>매도금액·수수료를 시나리오 가격으로 다시 계산한다</b> — {@code OrderExecutionService.priceOrder}와
	 * 같은 식·같은 라운딩이다. 매도금액을 원 단위로 {@code FLOOR}한 뒤 그 금액에 시장별 요율을 곱해 다시
	 * {@code FLOOR}한다. 가격이 바뀌면 수수료도 바뀌므로 본체 {@code fee}·{@link #returnRate}를 그대로 쓸 수
	 * 없다.
	 *
	 * @param feeRate {@link #feeRateOf}가 고른 시장별 요율
	 * @param buyBasis 배분 매수원가 + 배분 매수수수료. 0이면 {@link #returnRate}와 같은 이유로 {@code ZERO}
	 */
	static BigDecimal counterfactualReturnRate(
		BigDecimal price, BigDecimal quantity, long buyBasis, BigDecimal feeRate) {
		long amount = price.multiply(quantity).setScale(0, RoundingMode.FLOOR).longValueExact();
		long fee = BigDecimal.valueOf(amount)
			.multiply(feeRate)
			.setScale(0, RoundingMode.FLOOR)
			.longValueExact();
		if (buyBasis == 0L) {
			return BigDecimal.ZERO;
		}
		long realizedPnl = (amount - fee) - buyBasis;
		return BigDecimal.valueOf(realizedPnl)
			.divide(BigDecimal.valueOf(buyBasis), RETURN_RATE_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * 계약이 <b>분 단위</b>로 정한 값들의 차를 잰다 — {@code holdingMinutes}·{@code minutesAfterBuy}·
	 * {@code minutesBeforeSell}·{@code buyToNewsMinutes}가 전부 이것을 쓴다 (§파생 사실 계산).
	 *
	 * <p><b>{@code Duration.between(...).toMinutes()}를 그대로 쓰면 안 된다.</b> 체결 시각에는 소수 초가 붙어
	 * 있고({@code executed_at}이 {@code DATETIME(6)}이다) {@code toMinutes()}는 <b>0 방향으로 절삭</b>한다.
	 * 그래서 {@code 09:30:17.4 → 11:25:00}이 {@code 114}가 되는데 {@code docs/api/feedback.md}의 예시는
	 * {@code minutesAfterBuy: 115}다. <b>계약 예시가 재현되지 않으면 예시가 아니라 구현이 틀린 것이다.</b>
	 *
	 * <p><b>0 방향 절삭은 부호에 따라 방향이 뒤집힌다.</b> {@code buyToNewsMinutes}는 기사가 매수보다 이르면
	 * 음수인데, 그때는 절삭이 값을 <b>키운다</b>({@code -104.7 → -104}) — 양수에서 줄이던 것과 반대다. 두 끝점을
	 * 먼저 분으로 내리면 남은 차가 정확한 분 수이므로 <b>양쪽 부호에서 같은 규칙</b>이 된다.
	 */
	static int minutesBetween(LocalDateTime from, LocalDateTime to) {
		return (int)ChronoUnit.MINUTES.between(onMinuteBoundary(from), onMinuteBoundary(to));
	}

	/**
	 * 체결 시각을 <b>분봉·카드 구간 판정에 쓸 경계</b>로 내린다 — 초·소수 초를 버린다.
	 *
	 * <p><b>{@code toLocalTime()}을 그대로 쓰면 안 된다.</b> 체결 시각은 운영에서 항상 {@code 09:30:17.4xxxxx}
	 * 꼴인데 분봉의 {@code candle_time}과 카드의 {@code window_end}는 <b>소수 초가 없는 정시 값</b>이다.
	 * 그대로 비교하면 하한이 {@code !candleTime.isBefore(09:30:17.4)}로 <b>매수 분봉을 탈락시켜</b> §파생 사실
	 * 계산의 "양 끝 포함"이 깨지는데, 상한은 그대로 포함해 <b>비대칭으로</b> 어긋난다. 결과는 극값이 한 봉 밀린
	 * 채 조용히 틀리는 것이다 — 예외도 로그도 없다.
	 *
	 * <p><b>정시 픽스처로는 이 회귀가 잡히지 않는다.</b> 테스트가 {@code 09:30:00}으로 체결을 만들면 truncate가
	 * 있든 없든 같은 답이 나오므로, 픽스처의 체결 시각에 초·소수 초를 붙여야 이 자리가 실제로 검증된다.
	 */
	static LocalTime candleBoundary(LocalDateTime executedAt) {
		return onMinuteBoundary(executedAt).toLocalTime();
	}

	/**
	 * 분 경계로 내리는 규칙의 단일 출처 — {@link #candleBoundary}(분봉·카드 구간)와 {@link #minutesBetween}
	 * (분 단위 값)이 함께 쓴다.
	 *
	 * <p>두 곳이 서로 다른 규칙으로 내리면 "카드가 보유 구간에 들어왔는데 {@code minutesAfterBuy}가 음수"처럼
	 * 한 응답 안에서 앞뒤가 안 맞는 조합이 생긴다.
	 */
	static LocalDateTime onMinuteBoundary(LocalDateTime executedAt) {
		return executedAt.truncatedTo(ChronoUnit.MINUTES);
	}

	/**
	 * 확정 집계 행이 있을 때의 집단 비교 판정 — {@code holderCount < 5}면 {@code INSUFFICIENT_SAMPLE}(모집단
	 * 지표 3종 {@code null}, {@code yourMinutesToSell}만 채움), 그 외 {@code READY}다 (§C-4).
	 *
	 * <p>주식·코인이 같은 판정을 쓴다 — 갈리는 것은 <b>어느 행을 찾는가</b>(주식은 그 체결의 서비스 날짜, 코인은
	 * 카드 {@code occurred_at}의 KST 날짜)뿐이고 행을 찾은 뒤의 규칙은 같다.
	 */
	static PeerComparison toPeerComparison(
		PriceMovePeerStat stat, Long priceMoveId, Integer yourMinutesToSell) {
		if (stat.getHolderCount() < MIN_PEER_SAMPLE) {
			return new PeerComparison(
				PostSellFeedbackStatus.INSUFFICIENT_SAMPLE, priceMoveId, null, null, null, yourMinutesToSell);
		}
		return new PeerComparison(
			PostSellFeedbackStatus.READY,
			priceMoveId,
			stat.getHolderCount(),
			soldWithin30MinRate(stat),
			stat.getMedianMinutesToSell(),
			yourMinutesToSell);
	}

	/** {@code soldWithin30MinCount ÷ holderCount}, scale 4 {@code HALF_UP}. 배치는 개수만 저장한다. */
	private static BigDecimal soldWithin30MinRate(PriceMovePeerStat stat) {
		return BigDecimal.valueOf(stat.getSoldWithin30MinCount())
			.divide(BigDecimal.valueOf(stat.getHolderCount()), PEER_RATE_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * 확정 집계 행이 아직 없을 때(배치 전)의 기본값 — {@code NOT_YET}이고 지표가 전부 {@code null}이다.
	 *
	 * <p><b>{@code priceMoveId}조차 채우지 않는다.</b> 기준 카드는 알고 있지만 계약이 {@code NO_EVENT}에서
	 * {@code priceMoveId}를 포함한 전 필드를 {@code null}로 정했고, {@code NOT_YET}에서만 그 값을 채우면
	 * <b>배치가 돌아 판정이 바뀌는 순간 같은 체결의 {@code priceMoveId}가 값 → {@code null}로 사라지는 조합</b>이
	 * 생긴다.
	 */
	static PeerComparison peerComparisonNotYet() {
		return new PeerComparison(PostSellFeedbackStatus.NOT_YET, null, null, null, null, null);
	}

	/** 보유 구간에 기준 카드가 없을 때 — {@code NO_EVENT}가 {@code NOT_YET}보다 1순위다(§C-4). */
	static PeerComparison peerComparisonNoEvent() {
		return new PeerComparison(PostSellFeedbackStatus.NO_EVENT, null, null, null, null, null);
	}
}
