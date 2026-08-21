// 코인 가상 가격 세션 생성·조회 서비스의 소유권·상태·anchor fallback·재현성 검증을 mock으로 검증하는 단위 테스트다.
package com.finplay.api.domain.education.priceruntime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSessionStatus;
import com.finplay.api.domain.education.priceruntime.dto.response.PracticePriceSessionResponse;
import com.finplay.api.domain.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.PriceQueryService;
import com.finplay.api.domain.market.service.PriceQuoteDto;
import com.finplay.api.domain.market.service.PriceStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class PracticePriceSessionServiceTest {

	private static final Long USER_ID = 1L;
	private static final Long INSTRUMENT_ID = 10L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 10, 0);

	@Mock
	private InstrumentService instrumentService;
	@Mock
	private PriceQueryService priceQueryService;
	@Mock
	private PracticePriceSessionRepository practicePriceSessionRepository;

	private Clock clock;
	private PracticePriceSessionService service;

	@BeforeEach
	void setUp() {
		clock = Clock.fixed(NOW.atZone(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC"));
		service = new PracticePriceSessionService(
			instrumentService, priceQueryService, practicePriceSessionRepository, clock);
	}

	@Test
	void createSessionFailsWithNotFoundWhenInstrumentMissing() {
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		assertThatThrownBy(() -> service.createSession(USER_ID, INSTRUMENT_ID))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
		verify(practicePriceSessionRepository, never()).saveAndFlush(any());
	}

	@Test
	void createSessionFailsWithInstrumentNotTradableWhenMarketIsStock() {
		Instrument stock = stockInstrument();
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(stock);

		assertThatThrownBy(() -> service.createSession(USER_ID, INSTRUMENT_ID))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSTRUMENT_NOT_TRADABLE));
		verify(practicePriceSessionRepository, never()).saveAndFlush(any());
	}

	@Test
	void createSessionFailsWithInstrumentNotTradableWhenCryptoIsNotTradable() {
		Instrument nonTradableCrypto = cryptoInstrument(false);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(nonTradableCrypto);

		assertThatThrownBy(() -> service.createSession(USER_ID, INSTRUMENT_ID))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSTRUMENT_NOT_TRADABLE));
	}

	@Test
	void createSessionFailsWithAlreadyActiveWhenActiveSessionExists() {
		Instrument crypto = cryptoInstrument(true);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(crypto);
		when(practicePriceSessionRepository.existsByUserIdAndInstrumentIdAndStatus(
			USER_ID, INSTRUMENT_ID, PracticePriceSessionStatus.ACTIVE)).thenReturn(true);

		assertThatThrownBy(() -> service.createSession(USER_ID, INSTRUMENT_ID))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.PRACTICE_PRICE_SESSION_ALREADY_ACTIVE));
		verify(practicePriceSessionRepository, never()).saveAndFlush(any());
	}

	@Test
	void createSessionMapsConcurrentUniqueViolationToAlreadyActive() {
		Instrument crypto = cryptoInstrument(true);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(crypto);
		when(practicePriceSessionRepository.existsByUserIdAndInstrumentIdAndStatus(
			USER_ID, INSTRUMENT_ID, PracticePriceSessionStatus.ACTIVE)).thenReturn(false);
		when(priceQueryService.getPriceQuote(crypto))
			.thenReturn(new PriceQuoteDto(new BigDecimal("100.00000000"), NOW, PriceStatus.AVAILABLE, null));
		when(practicePriceSessionRepository.saveAndFlush(any()))
			.thenThrow(new DataIntegrityViolationException("동시 생성 unique 위반"));

		assertThatThrownBy(() -> service.createSession(USER_ID, INSTRUMENT_ID))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.PRACTICE_PRICE_SESSION_ALREADY_ACTIVE));
	}

	@Test
	void createSessionUsesRealValidPriceAsAnchorWhenAvailable() {
		Instrument crypto = cryptoInstrument(true);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(crypto);
		when(practicePriceSessionRepository.existsByUserIdAndInstrumentIdAndStatus(
			USER_ID, INSTRUMENT_ID, PracticePriceSessionStatus.ACTIVE)).thenReturn(false);
		when(priceQueryService.getPriceQuote(crypto))
			.thenReturn(new PriceQuoteDto(new BigDecimal("54321.5"), NOW, PriceStatus.AVAILABLE, null));
		when(practicePriceSessionRepository.saveAndFlush(any()))
			.thenAnswer(invocation -> invocation.getArgument(0));

		PracticePriceSessionResponse response = service.createSession(USER_ID, INSTRUMENT_ID);

		assertThat(response.startPrice()).isEqualByComparingTo("54321.50000000");
		assertThat(response.currentPrice()).isEqualByComparingTo("54321.50000000");
		assertThat(response.currentTick()).isZero();
		assertThat(response.status()).isEqualTo(PracticePriceSessionStatus.ACTIVE);
		assertThat(response.generatorVersion()).isEqualTo(PracticePriceGeneratorV1.VERSION);
	}

	@Test
	void createSessionUsesFallbackTenThousandWhenPriceUnavailable() {
		Instrument crypto = cryptoInstrument(true);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(crypto);
		when(practicePriceSessionRepository.existsByUserIdAndInstrumentIdAndStatus(
			USER_ID, INSTRUMENT_ID, PracticePriceSessionStatus.ACTIVE)).thenReturn(false);
		when(priceQueryService.getPriceQuote(crypto))
			.thenReturn(new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null));
		when(practicePriceSessionRepository.saveAndFlush(any()))
			.thenAnswer(invocation -> invocation.getArgument(0));

		PracticePriceSessionResponse response = service.createSession(USER_ID, INSTRUMENT_ID);

		assertThat(response.startPrice()).isEqualByComparingTo("10000.00000000");
		assertThat(response.currentPrice()).isEqualByComparingTo("10000.00000000");
	}

	// 036-remove-crypto-stale-status 회귀 — 관측 시각이 오래돼도(과거엔 STALE) AVAILABLE이면 fallback anchor(10000)가
	// 아니라 실시세를 anchor로 쓴다.
	@Test
	void createSessionUsesRealPriceAsAnchorEvenWhenObservationIsHoursOldButStatusIsAvailable() {
		Instrument crypto = cryptoInstrument(true);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(crypto);
		when(practicePriceSessionRepository.existsByUserIdAndInstrumentIdAndStatus(
			USER_ID, INSTRUMENT_ID, PracticePriceSessionStatus.ACTIVE)).thenReturn(false);
		when(priceQueryService.getPriceQuote(crypto))
			.thenReturn(new PriceQuoteDto(new BigDecimal("54321.5"), NOW.minusHours(3), PriceStatus.AVAILABLE, null));
		when(practicePriceSessionRepository.saveAndFlush(any()))
			.thenAnswer(invocation -> invocation.getArgument(0));

		PracticePriceSessionResponse response = service.createSession(USER_ID, INSTRUMENT_ID);

		assertThat(response.startPrice()).isEqualByComparingTo("54321.50000000");
		assertThat(response.currentPrice()).isEqualByComparingTo("54321.50000000");
	}

	@Test
	void createSessionPersistsInitialStateWithTickZeroAndCurrentPriceEqualToStartPrice() {
		Instrument crypto = cryptoInstrument(true);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(crypto);
		when(practicePriceSessionRepository.existsByUserIdAndInstrumentIdAndStatus(
			USER_ID, INSTRUMENT_ID, PracticePriceSessionStatus.ACTIVE)).thenReturn(false);
		when(priceQueryService.getPriceQuote(crypto))
			.thenReturn(new PriceQuoteDto(new BigDecimal("1000"), NOW, PriceStatus.AVAILABLE, null));
		when(practicePriceSessionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
			PracticePriceSession saved = invocation.getArgument(0);
			assertThat(saved.getStatus()).isEqualTo(PracticePriceSessionStatus.ACTIVE);
			assertThat(saved.getCurrentTick()).isZero();
			assertThat(saved.getCurrentPrice()).isEqualByComparingTo(saved.getStartPrice());
			return saved;
		});

		service.createSession(USER_ID, INSTRUMENT_ID);

		verify(practicePriceSessionRepository).saveAndFlush(any());
	}

	@Test
	void getSessionFailsWithNotFoundWhenOwnedByAnotherUser() {
		when(practicePriceSessionRepository.findByIdAndUserId(99L, USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getSession(USER_ID, 99L))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	void getSessionFailsWithNotFoundWhenSessionDoesNotExist() {
		when(practicePriceSessionRepository.findByIdAndUserId(404L, USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getSession(USER_ID, 404L))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	void getSessionReturnsSessionWhenTickZeroAndCurrentPriceMatchesStartPrice() {
		PracticePriceSession session = PracticePriceSession.create(
			USER_ID, INSTRUMENT_ID, 12345L, (short)PracticePriceGeneratorV1.VERSION,
			new BigDecimal("10000.00000000"), NOW);
		when(practicePriceSessionRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(session));

		PracticePriceSessionResponse response = service.getSession(USER_ID, 1L);

		assertThat(response.currentTick()).isZero();
		assertThat(response.currentPrice()).isEqualByComparingTo("10000.00000000");
	}

	// verifyPriceSeriesConsistency 방어 로직이 실제로 발동하는지 확인한다. 저장된 currentTick(=1)까지 v1
	// 생성기를 재실행하면 seed=12345 tick=1의 값은 10092.29000000(plan.md 계약대로 독립 재계산한 값)인데,
	// 리플렉션으로 currentPrice를 다른 값으로 조작해 저장 데이터 손상을 흉내낸다.
	@Test
	void getSessionThrowsInternalErrorWhenStoredCurrentPriceDoesNotMatchRegeneratedSeries() throws Exception {
		PracticePriceSession session = PracticePriceSession.create(
			USER_ID, INSTRUMENT_ID, 12345L, (short)PracticePriceGeneratorV1.VERSION,
			new BigDecimal("10000.00000000"), NOW);
		setField(session, "currentTick", (short)1);
		setField(session, "currentPrice", new BigDecimal("999999.00000000"));
		when(practicePriceSessionRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(session));

		assertThatThrownBy(() -> service.getSession(USER_ID, 1L))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));
	}

	@Test
	void getSessionSucceedsWhenStoredCurrentPriceMatchesRegeneratedSeriesAtTickOne() throws Exception {
		PracticePriceSession session = PracticePriceSession.create(
			USER_ID, INSTRUMENT_ID, 12345L, (short)PracticePriceGeneratorV1.VERSION,
			new BigDecimal("10000.00000000"), NOW);
		setField(session, "currentTick", (short)1);
		setField(session, "currentPrice", new BigDecimal("10092.29000000"));
		when(practicePriceSessionRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(session));

		PracticePriceSessionResponse response = service.getSession(USER_ID, 1L);

		assertThat(response.currentTick()).isEqualTo(1);
		assertThat(response.currentPrice()).isEqualByComparingTo("10092.29000000");
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		var field = PracticePriceSession.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	private Instrument stockInstrument() {
		return Instrument.create(Market.STOCK, "005930", "삼성전자", new BigDecimal("1"), 0L, true, NOW);
	}

	private Instrument cryptoInstrument(boolean tradable) {
		return Instrument.create(Market.CRYPTO, "BTC", "비트코인", new BigDecimal("0.00000001"), 0L, tradable, NOW);
	}
}
