package com.finplay.api.domain.education.priceruntime.service;

import com.finplay.api.domain.education.priceruntime.dto.response.PracticePriceSessionResponse;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSessionStatus;
import com.finplay.api.domain.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.PriceQueryService;
import com.finplay.api.domain.market.service.PriceQuoteDto;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticePriceSessionService {

	private static final BigDecimal FALLBACK_START_PRICE = new BigDecimal("10000.00000000");
	private static final int PRICE_SCALE = 8;

	private final InstrumentService instrumentService;
	private final PriceQueryService priceQueryService;
	private final PracticePriceSessionRepository practicePriceSessionRepository;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();

	@Transactional
	public PracticePriceSessionResponse createSession(Long userId, Long instrumentId) {
		Instrument instrument = instrumentService.getInstrumentEntity(instrumentId);
		if (instrument.getMarket() != Market.CRYPTO || !instrument.isTradable()) {
			throw new BusinessException(ErrorCode.INSTRUMENT_NOT_TRADABLE);
		}
		if (practicePriceSessionRepository.existsByUserIdAndInstrumentIdAndStatus(
			userId, instrumentId, PracticePriceSessionStatus.ACTIVE)) {
			throw new BusinessException(ErrorCode.PRACTICE_PRICE_SESSION_ALREADY_ACTIVE);
		}

		BigDecimal startPrice = resolveStartPrice(instrument);
		long seed = secureRandom.nextLong();
		PracticePriceSession session = PracticePriceSession.create(
			userId, instrumentId, seed, (short)PracticePriceGeneratorV1.VERSION, startPrice,
			LocalDateTime.now(clock));
		try {
			return PracticePriceSessionResponse.from(practicePriceSessionRepository.saveAndFlush(session));
		} catch (DataIntegrityViolationException concurrentDuplicate) {
			throw new BusinessException(ErrorCode.PRACTICE_PRICE_SESSION_ALREADY_ACTIVE);
		}
	}

	@Transactional(readOnly = true)
	public PracticePriceSessionResponse getSession(Long userId, Long sessionId) {
		PracticePriceSession session = practicePriceSessionRepository
			.findByIdAndUserId(sessionId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
		verifyPriceSeriesConsistency(session);
		return PracticePriceSessionResponse.from(session);
	}

	private BigDecimal resolveStartPrice(Instrument instrument) {
		PriceQuoteDto quote = priceQueryService.getPriceQuote(instrument);
		if (quote.status() == PriceStatus.AVAILABLE) {
			return quote.price().setScale(PRICE_SCALE, RoundingMode.HALF_UP);
		}
		return FALLBACK_START_PRICE;
	}

	private void verifyPriceSeriesConsistency(PracticePriceSession session) {
		BigDecimal regenerated = session.getStartPrice();
		for (int tick = 1; tick <= session.getCurrentTick(); tick++) {
			regenerated = PracticePriceGeneratorV1.nextPrice(
				session.getSeed(), tick, regenerated, session.getStartPrice());
		}
		if (regenerated.compareTo(session.getCurrentPrice()) != 0) {
			throw new BusinessException(ErrorCode.INTERNAL_ERROR);
		}
	}
}
