// 튜토리얼 참고용 합성 랜덤워크 시세를 요청마다 즉석 생성하는 서비스(저장소 없음)
package com.finplay.api.education.service;

import com.finplay.api.education.dto.response.SyntheticPriceSeriesResponse;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.service.PriceQueryService;
import com.finplay.api.market.service.PriceQuoteDto;
import com.finplay.api.market.service.PriceStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SyntheticPriceService {

	// 5분 / 3초 = 100틱 (시작가를 첫 틱으로 포함)
	private static final int TICK_COUNT = 100;
	private static final int TICK_SECONDS = 3;
	// 실제 현재가 조회가 실패했을 때(PRICE_UNAVAILABLE 등)의 fallback 시작가.
	// 이 종목의 실제 마지막 종가를 별도로 저장·조회하는 저장소가 없고, 합성 시세 자체가
	// 실제 판정에 쓰이지 않는 튜토리얼 참고용 차트이므로 임의의 고정 기본값을 사용한다.
	private static final BigDecimal FALLBACK_START_PRICE = BigDecimal.valueOf(10_000);
	// 틱당 변동폭: 이전 값 대비 -1%~+1% 균등분포
	private static final double TICK_CHANGE_RATIO = 0.01;
	// 시작가의 50% 미만으로는 떨어지지 않도록 clamp
	private static final BigDecimal MIN_PRICE_RATIO = BigDecimal.valueOf(0.5);

	private final InstrumentService instrumentService;
	private final PriceQueryService priceQueryService;

	@Transactional(readOnly = true)
	public SyntheticPriceSeriesResponse generateSeries(Long instrumentId) {
		Instrument instrument = instrumentService.getInstrumentEntity(instrumentId);
		BigDecimal startPrice = resolveStartPrice(instrument);
		List<BigDecimal> prices = generateRandomWalk(startPrice);
		return new SyntheticPriceSeriesResponse(instrument.getName(), TICK_SECONDS, prices);
	}

	private BigDecimal resolveStartPrice(Instrument instrument) {
		PriceQuoteDto quote = priceQueryService.getPriceQuote(instrument);
		if (quote.status() == PriceStatus.AVAILABLE) {
			return quote.price();
		}
		return FALLBACK_START_PRICE;
	}

	private List<BigDecimal> generateRandomWalk(BigDecimal startPrice) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		BigDecimal floor = startPrice.multiply(MIN_PRICE_RATIO);
		List<BigDecimal> prices = new ArrayList<>(TICK_COUNT);
		BigDecimal current = roundPrice(startPrice);
		prices.add(current);
		for (int tick = 1; tick < TICK_COUNT; tick++) {
			double changeRatio = random.nextDouble(-TICK_CHANGE_RATIO, TICK_CHANGE_RATIO);
			BigDecimal multiplier = BigDecimal.valueOf(1 + changeRatio);
			BigDecimal next = current.multiply(multiplier);
			if (next.compareTo(floor) < 0) {
				next = floor;
			}
			current = roundPrice(next);
			prices.add(current);
		}
		return prices;
	}

	private BigDecimal roundPrice(BigDecimal price) {
		return price.setScale(0, RoundingMode.HALF_UP);
	}
}
