package com.finplay.api.domain.order.listener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.event.CryptoPriceUpdatedEvent;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.order.service.ExitPlanFillService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ExitPlanTriggerListenerTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 10, 0, 0);

	private final InstrumentService instrumentService = mock(InstrumentService.class);
	private final ExitPlanRepository exitPlanRepository = mock(ExitPlanRepository.class);
	private final ExitPlanFillService exitPlanFillService = mock(ExitPlanFillService.class);

	private final ExitPlanTriggerListener listener = new ExitPlanTriggerListener(
		instrumentService, exitPlanRepository, exitPlanFillService);

	@BeforeEach
	void setUp() {}

	@Test
	@DisplayName("코인 목록에 없는 심볼의 가격 틱은 조용히 무시한다 — plan 조회·체결 시도를 하지 않는다")
	void onPriceUpdatedIgnoresSymbolNotInInstrumentList() {
		CryptoPriceUpdatedEvent event = new CryptoPriceUpdatedEvent("NOT_LISTED", new BigDecimal("100"), NOW, NOW);
		when(instrumentService.findEntityByMarketAndSymbol(Market.CRYPTO, "NOT_LISTED")).thenReturn(Optional.empty());

		assertThatCode(() -> listener.onPriceUpdated(event)).doesNotThrowAnyException();

		verifyNoInteractions(exitPlanRepository, exitPlanFillService);
	}

	@Test
	@DisplayName("여러 후보 중 하나가 예외를 던져도 나머지 후보는 계속 처리된다 — 건별 catch로 격리")
	void onPriceUpdatedIsolatesFailureOfOneCandidateFromTheRest() {
		Instrument instrument = instrument();
		CryptoPriceUpdatedEvent event = new CryptoPriceUpdatedEvent("BTC", new BigDecimal("110000"), NOW, NOW);
		when(instrumentService.findEntityByMarketAndSymbol(Market.CRYPTO, "BTC")).thenReturn(Optional.of(instrument));

		ExitPlan failingCandidate = exitPlanWithId(1L);
		ExitPlan succeedingCandidate = exitPlanWithId(2L);
		when(exitPlanRepository.findPendingExitPlansToFill(instrument.getId(), event.price()))
			.thenReturn(List.of(failingCandidate, succeedingCandidate));

		org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
			.when(exitPlanFillService).fillIfPending(1L, event.price());

		assertThatCode(() -> listener.onPriceUpdated(event)).doesNotThrowAnyException();

		verify(exitPlanFillService, times(1)).fillIfPending(1L, event.price());
		verify(exitPlanFillService, times(1)).fillIfPending(2L, event.price());
	}

	@Test
	@DisplayName("handle() 내부에서 발생한 어떤 예외도 onPriceUpdated 바깥으로 전파하지 않는다 — 가격 피드 수신 스레드 보호")
	void onPriceUpdatedNeverPropagatesExceptionsToCaller() {
		when(instrumentService.findEntityByMarketAndSymbol(any(), any()))
			.thenThrow(new RuntimeException("instrument lookup failed"));

		CryptoPriceUpdatedEvent event = new CryptoPriceUpdatedEvent("BTC", new BigDecimal("100000"), NOW, NOW);

		assertThatCode(() -> listener.onPriceUpdated(event)).doesNotThrowAnyException();
		verify(exitPlanFillService, never()).fillIfPending(anyLong(), any());
	}

	private static Instrument instrument() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1000"), 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 1L);
		return instrument;
	}

	private static ExitPlan exitPlanWithId(Long id) {
		ExitPlan plan = mock(ExitPlan.class);
		when(plan.getId()).thenReturn(id);
		return plan;
	}
}
