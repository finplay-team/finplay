// 튜토리얼 샘플 종목의 결정적 사인파 가격을 계산해 실제 시세 인프라(StockPriceProvider·PriceStore)를 완전히 우회하는 서비스
package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TutorialSampleInstrumentPriceService {

	private static final BigDecimal STOCK_BASE_PRICE = new BigDecimal("50000.00000000");
	private static final BigDecimal CRYPTO_BASE_PRICE = new BigDecimal("10000.00000000");
	private static final double AMPLITUDE = 0.03;
	private static final long PERIOD_SECONDS = 180;
	private static final int PRICE_SCALE = 8;

	private final Clock clock;

	// 항상 PriceStatus.AVAILABLE — 샘플 종목은 절대 UNAVAILABLE을 반환하지 않는다 (SANDBOX-002)
	public PriceQuoteDto getPriceQuote(Instrument instrument) {
		return new PriceQuoteDto(calculatePrice(instrument), LocalDateTime.now(clock), PriceStatus.AVAILABLE, null);
	}

	// (instrument, now)만으로 결정되는 순수 함수 — 저장 상태 없이 재기동·다중 인스턴스에서도 같은 값을 재현한다
	private BigDecimal calculatePrice(Instrument instrument) {
		BigDecimal basePrice = instrument.getMarket() == Market.STOCK ? STOCK_BASE_PRICE : CRYPTO_BASE_PRICE;
		double phase = (instrument.getId() % 7) * (Math.PI / 7);
		long epochSeconds = Instant.now(clock).getEpochSecond();
		double rate = AMPLITUDE * Math.sin(2 * Math.PI * epochSeconds / PERIOD_SECONDS + phase);
		return basePrice.multiply(BigDecimal.valueOf(1 + rate)).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
	}
}
