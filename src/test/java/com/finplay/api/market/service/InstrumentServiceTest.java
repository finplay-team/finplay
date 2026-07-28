// market 파라미터 유무에 따라 올바른 Repository 메서드로 위임하는지, 단건 조회가 존재/부재 각각에서 올바르게 동작하는지 검증하는 단위 테스트다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.response.InstrumentResponse;
import com.finplay.api.market.repository.InstrumentRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InstrumentServiceTest {

	@Test
	void getInstrumentsFindsAllOrderedByIdWhenMarketIsNull() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, LocalDateTime.now());
		when(instrumentRepository.findAllByOrderByIdAsc()).thenReturn(List.of(instrument));
		InstrumentService instrumentService = new InstrumentService(instrumentRepository);

		List<InstrumentResponse> responses = instrumentService.getInstruments(null);

		assertThat(responses).hasSize(1);
		assertThat(responses.get(0).symbol()).isEqualTo("005930");
		verify(instrumentRepository).findAllByOrderByIdAsc();
		verifyNoMoreInteractions(instrumentRepository);
	}

	@Test
	void getInstrumentsFindsByMarketWhenMarketIsStock() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, LocalDateTime.now());
		when(instrumentRepository.findByMarketOrderByIdAsc(Market.STOCK)).thenReturn(List.of(instrument));
		InstrumentService instrumentService = new InstrumentService(instrumentRepository);

		List<InstrumentResponse> responses = instrumentService.getInstruments(Market.STOCK);

		assertThat(responses).hasSize(1);
		assertThat(responses.get(0).market()).isEqualTo("STOCK");
		verify(instrumentRepository).findByMarketOrderByIdAsc(Market.STOCK);
		verifyNoMoreInteractions(instrumentRepository);
	}

	@Test
	void getInstrumentsFindsByMarketWhenMarketIsCrypto() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true, LocalDateTime.now());
		when(instrumentRepository.findByMarketOrderByIdAsc(Market.CRYPTO)).thenReturn(List.of(instrument));
		InstrumentService instrumentService = new InstrumentService(instrumentRepository);

		List<InstrumentResponse> responses = instrumentService.getInstruments(Market.CRYPTO);

		assertThat(responses).hasSize(1);
		assertThat(responses.get(0).market()).isEqualTo("CRYPTO");
		verify(instrumentRepository).findByMarketOrderByIdAsc(Market.CRYPTO);
		verifyNoMoreInteractions(instrumentRepository);
	}

	@Test
	void getInstrumentReturnsResponseWhenInstrumentExists() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, LocalDateTime.now());
		when(instrumentRepository.findById(1L)).thenReturn(Optional.of(instrument));
		InstrumentService instrumentService = new InstrumentService(instrumentRepository);

		InstrumentResponse response = instrumentService.getInstrument(1L);

		assertThat(response.market()).isEqualTo("STOCK");
		assertThat(response.symbol()).isEqualTo("005930");
		assertThat(response.name()).isEqualTo("삼성전자");
		verify(instrumentRepository).findById(1L);
		verifyNoMoreInteractions(instrumentRepository);
	}

	@Test
	void getInstrumentThrowsNotFoundBusinessExceptionWhenInstrumentMissing() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		when(instrumentRepository.findById(999L)).thenReturn(Optional.empty());
		InstrumentService instrumentService = new InstrumentService(instrumentRepository);

		assertThatThrownBy(() -> instrumentService.getInstrument(999L))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
		verify(instrumentRepository).findById(999L);
		verifyNoMoreInteractions(instrumentRepository);
	}
}
