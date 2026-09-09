package com.finplay.api.domain.education.synthetic.service;

import com.finplay.api.domain.education.synthetic.dto.response.SyntheticPriceSeriesResponse;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.PriceQueryService;
import com.finplay.api.domain.market.service.PriceQuoteDto;
import com.finplay.api.domain.market.service.PriceStatus;
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

	private static final int TICK_COUNT = 100;
	private static final int TICK_SECONDS = 3;
	private static final BigDecimal FALLBACK_START_PRICE = BigDecimal.valueOf(10_000);
	private static final double TICK_CHANGE_RATIO = 0.01;
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
