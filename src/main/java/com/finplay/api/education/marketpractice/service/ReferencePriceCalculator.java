// intention의 PRICE/PERCENT 손절·익절 기준으로 참조 절대 가격선을 매 요청마다 재계산하는 서비스(019 공식 재사용)
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.education.marketpractice.domain.ExitPreset;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * docs/specs/026-market-order-practice-tutorial plan.md "3단계 참조 가격선 계산" 절의 규칙을 구현한다. 결과를
 * 저장하지 않고 매 요청마다 다시 계산한다.
 *
 * <p><b>PERCENT 분기에 대한 설계 판단:</b> {@code docs/specs/019-exit-price-policy}는 아직 "문서 설계 확정,
 * production 구현 미착수" 상태다(spec.md 1행) — 실제 {@link com.finplay.api.education.domain.PracticeIntention}
 * 레코드와 {@link com.finplay.api.education.dto.request.PracticeIntentionCreateRequest}는 {@code exitPriceType}·
 * {@code stopLossRate}·{@code takeProfitRate} 필드를 아직 갖지 않고 절대 가격(PRICE)만 저장한다. 따라서 이
 * 서비스는 019 공식대로 {@link #calculateFromPercent}를 독립적으로 제공해 두지만(단위 테스트 대상,
 * tasks.md 2번이 요구하는 "PERCENT 반올림 포함" 검증), {@link #calculate(ResolvedPracticeChainDto)}는 현재
 * chain 해석 결과({@link ResolvedPracticeChainDto})가 절대 가격만 담고 있으므로 {@link #calculateFromPrice}만
 * 실제로 호출한다. 019가 production에 들어와 intention에 rate 필드가 추가되면 그때 이 메서드의 분기를
 * 넓힌다.
 *
 * <p><b>2026-08-19 (042).</b> {@link #calculateFromPercent}의 첫 production 소비자가 튜토리얼 손절·익절
 * 프리셋이다. intention 경로는 위 설명대로 여전히 PRICE만 쓰며, 프리셋은 intention을 거치지 않고
 * {@link #calculateFromPreset}으로 바로 들어온다.
 */
@Service
public class ReferencePriceCalculator {

	private static final int PRICE_SCALE = 8;
	private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;
	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	/**
	 * 해석된 chain의 intention 절대 가격 기준으로 참조 가격선을 계산한다. intention이 유실돼 chain 자체가 없거나
	 * 절대 가격 필드가 없으면(계산 불가) 빈 값을 반환한다.
	 */
	public Optional<ReferencePriceLines> calculate(ResolvedPracticeChainDto chain) {
		if (chain == null) {
			return Optional.empty();
		}
		return calculateFromPrice(chain.intentionStopLoss(), chain.intentionTakeProfit());
	}

	/** intention.exitPriceType == PRICE일 때: 저장된 절대 가격을 scale 8 HALF_UP으로 정규화해 그대로 쓴다. */
	public Optional<ReferencePriceLines> calculateFromPrice(BigDecimal stopLoss, BigDecimal takeProfit) {
		if (stopLoss == null || takeProfit == null) {
			return Optional.empty();
		}
		return Optional.of(new ReferencePriceLines(
			stopLoss.setScale(PRICE_SCALE, ROUNDING_MODE), takeProfit.setScale(PRICE_SCALE, ROUNDING_MODE)));
	}

	/**
	 * intention.exitPriceType == PERCENT일 때: {@code entryPrice}(buyTrade.entryPrice) 기준으로 019 공식대로
	 * 계산한다. 중간 나눗셈은 {@link MathContext#DECIMAL128}로 수행하고 최종 가격만 scale 8 HALF_UP으로 한 번
	 * 반올림한다(019 spec.md "계산과 snapshot 규칙").
	 */
	public Optional<ReferencePriceLines> calculateFromPercent(
		BigDecimal entryPrice, BigDecimal stopLossRate, BigDecimal takeProfitRate) {
		if (entryPrice == null || stopLossRate == null || takeProfitRate == null) {
			return Optional.empty();
		}
		BigDecimal stopLossFactor = BigDecimal.ONE.subtract(stopLossRate.divide(HUNDRED, MathContext.DECIMAL128));
		BigDecimal takeProfitFactor = BigDecimal.ONE.add(takeProfitRate.divide(HUNDRED, MathContext.DECIMAL128));
		BigDecimal referenceStopLossPrice = entryPrice.multiply(stopLossFactor).setScale(PRICE_SCALE, ROUNDING_MODE);
		BigDecimal referenceTakeProfitPrice = entryPrice.multiply(takeProfitFactor)
			.setScale(PRICE_SCALE, ROUNDING_MODE);
		return Optional.of(new ReferencePriceLines(referenceStopLossPrice, referenceTakeProfitPrice));
	}

	/**
	 * 튜토리얼 프리셋({@link ExitPreset})의 손절률·익절률을 진입 체결가에 적용해 기준선을 계산한다
	 * (042 EXITPRESET-004). {@code preset}이 null이면 미선택으로 보고 기본 프리셋을 적용한다
	 * (EXITPRESET-002).
	 *
	 * <p>여기만 {@link Optional}이 아니라 값을 그대로 돌려준다 — 호출 자리가 매수 체결 트랜잭션이라 체결가가
	 * 항상 있고, 없다면 그 자리에서 실패해야 할 결함이지 빈 값으로 흘려보낼 상태가 아니다.
	 *
	 * <p><b>체결가를 먼저 scale 8로 정규화한다.</b> EXITPRESET-002의 "현행과 정확히 같은 값"은 현행 코드가
	 * {@code trade.getPrice().setScale(8, HALF_UP)}을 <b>먼저</b> 하고 곱하기 때문에 성립한다. 정규화를
	 * 호출자에게 맡기면 그 전제가 호출 지점마다 다시 지켜져야 하고, 어기면 scale 9 이하 자리에서 조용히
	 * 갈린다. snapshot의 {@code entry_price}가 DECIMAL(18,8)이라 어차피 저장되는 값도 이 값이다.
	 */
	public ReferencePriceLines calculateFromPreset(BigDecimal entryPrice, ExitPreset preset) {
		ExitPreset applied = preset == null ? ExitPreset.DEFAULT : preset;
		BigDecimal normalizedEntryPrice = entryPrice == null
			? null
			: entryPrice.setScale(PRICE_SCALE, ROUNDING_MODE);
		return calculateFromPercent(normalizedEntryPrice, applied.stopLossRate(), applied.takeProfitRate())
			.orElseThrow(() -> new IllegalArgumentException("진입 체결가 없이 손절·익절 기준선을 계산할 수 없습니다."));
	}
}
