// holding 관찰 세션 가격원 파사드의 fallback·owner·instrument 검증 분기를 mock으로 검증하는 단위 테스트다.
package com.finplay.api.domain.education.priceruntime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PracticePriceObservationServiceTest {

	private static final Long USER_ID = 1L;
	private static final Long BUY_TRADE_ID = 30L;
	private static final Long INSTRUMENT_ID = 100L;
	private static final Long SESSION_ID = 7L;
	private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 11, 10, 0);

	@Mock
	private TradeService tradeService;
	@Mock
	private PracticePriceSessionRepository practicePriceSessionRepository;

	private PracticePriceObservationService service;

	@BeforeEach
	void setUp() {
		service = new PracticePriceObservationService(tradeService, practicePriceSessionRepository);
	}

	@Test
	void findObservationPriceReturnsEmptyWhenBuyTradeHasNoPracticeSession() {
		when(tradeService.findPracticePriceSessionId(BUY_TRADE_ID)).thenReturn(Optional.empty());

		Optional<BigDecimal> price = service.findObservationPrice(USER_ID, BUY_TRADE_ID, INSTRUMENT_ID);

		assertThat(price).isEmpty();
	}

	@Test
	void findObservationPriceReturnsSessionCurrentPriceWhenOwnerAndInstrumentMatch() {
		when(tradeService.findPracticePriceSessionId(BUY_TRADE_ID)).thenReturn(Optional.of(SESSION_ID));
		PracticePriceSession session = PracticePriceSession.create(
			USER_ID, INSTRUMENT_ID, 12345L, (short)1, new BigDecimal("54321.50000000"), CREATED_AT);
		when(practicePriceSessionRepository.findByIdAndUserId(SESSION_ID, USER_ID))
			.thenReturn(Optional.of(session));

		Optional<BigDecimal> price = service.findObservationPrice(USER_ID, BUY_TRADE_ID, INSTRUMENT_ID);

		assertThat(price).isPresent();
		assertThat(price.get()).isEqualByComparingTo("54321.50000000");
	}

	@Test
	void findObservationPriceThrowsEvidenceMissingWhenSessionNotOwnedByRequestingUser() {
		when(tradeService.findPracticePriceSessionId(BUY_TRADE_ID)).thenReturn(Optional.of(SESSION_ID));
		when(practicePriceSessionRepository.findByIdAndUserId(SESSION_ID, USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.findObservationPrice(USER_ID, BUY_TRADE_ID, INSTRUMENT_ID))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));
	}

	@Test
	void findObservationPriceThrowsEvidenceMissingWhenSessionInstrumentDiffersFromRequested() {
		when(tradeService.findPracticePriceSessionId(BUY_TRADE_ID)).thenReturn(Optional.of(SESSION_ID));
		Long otherInstrumentId = 999L;
		PracticePriceSession session = PracticePriceSession.create(
			USER_ID, otherInstrumentId, 12345L, (short)1, new BigDecimal("54321.50000000"), CREATED_AT);
		when(practicePriceSessionRepository.findByIdAndUserId(SESSION_ID, USER_ID))
			.thenReturn(Optional.of(session));

		assertThatThrownBy(() -> service.findObservationPrice(USER_ID, BUY_TRADE_ID, INSTRUMENT_ID))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));
	}
}
