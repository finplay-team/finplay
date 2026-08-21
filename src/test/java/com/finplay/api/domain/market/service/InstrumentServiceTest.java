// market 파라미터 유무에 따라 올바른 Repository 메서드로 위임하는지, 거래 가능 여부·샌드박스 샘플 종목 판별이 올바르게 동작하는지 검증하는 단위 테스트다.
package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.dto.response.InstrumentResponse;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
	void getTradableInstrumentEntityReturnsInstrumentWhenTradable() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, LocalDateTime.now());
		when(instrumentRepository.findById(1L)).thenReturn(Optional.of(instrument));
		InstrumentService instrumentService = new InstrumentService(instrumentRepository);

		Instrument result = instrumentService.getTradableInstrumentEntity(1L);

		assertThat(result.getSymbol()).isEqualTo("005930");
		verify(instrumentRepository).findById(1L);
		verifyNoMoreInteractions(instrumentRepository);
	}

	@Test
	void getTradableInstrumentEntityThrowsValidationErrorWhenInstrumentMissing() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		when(instrumentRepository.findById(999L)).thenReturn(Optional.empty());
		InstrumentService instrumentService = new InstrumentService(instrumentRepository);

		assertThatThrownBy(() -> instrumentService.getTradableInstrumentEntity(999L))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
		verify(instrumentRepository).findById(999L);
		verifyNoMoreInteractions(instrumentRepository);
	}

	@Test
	void getTradableInstrumentEntityThrowsValidationErrorWhenInstrumentNotTradable() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, false, LocalDateTime.now());
		when(instrumentRepository.findById(1L)).thenReturn(Optional.of(instrument));
		InstrumentService instrumentService = new InstrumentService(instrumentRepository);

		assertThatThrownBy(() -> instrumentService.getTradableInstrumentEntity(1L))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
		verify(instrumentRepository).findById(1L);
		verifyNoMoreInteractions(instrumentRepository);
	}

	// 회귀: 커뮤니티 종목 태그(COM-004)는 tradable=true인 튜토리얼 샘플 종목도 거부해야 한다(spec 031
	// SANDBOX-001). tradable=true만으로는 태그를 허용하면 안 되고 isTutorialSample도 함께 봐야 한다.
	@Test
	void getTradableInstrumentEntityThrowsValidationErrorWhenInstrumentIsTutorialSampleEvenIfTradable() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		Instrument instrument = Instrument.create(
			Market.STOCK, "SANDBOX_STK_1", "연습용 주식 A", BigDecimal.valueOf(100), 10000L, true, LocalDateTime.now());
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		when(instrumentRepository.findById(1L)).thenReturn(Optional.of(instrument));
		InstrumentService instrumentService = new InstrumentService(instrumentRepository);

		assertThatThrownBy(() -> instrumentService.getTradableInstrumentEntity(1L))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
		verify(instrumentRepository).findById(1L);
		verifyNoMoreInteractions(instrumentRepository);
	}

	// 회귀: 외부 수집·AI 피드백 배치의 시장 단위 순회는 샌드박스 샘플 종목을 빼고 돌아야 한다(이슈 #406).
	// 두 메서드가 같은 파인더를 부르면 구분 자체가 사라지므로, 어느 파인더를 부르는지까지 단정한다.
	@Test
	void getRealInstrumentEntitiesExcludesTutorialSampleInstruments() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		Instrument real = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, LocalDateTime.now());
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(real));
		InstrumentService instrumentService = new InstrumentService(instrumentRepository);

		List<Instrument> result = instrumentService.getRealInstrumentEntities(Market.STOCK);

		assertThat(result).extracting(Instrument::getSymbol).containsExactly("005930");
		verify(instrumentRepository).findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK);
		verifyNoMoreInteractions(instrumentRepository);
	}

	// 반대 방향 회귀 — 종목 목록(GET /api/instruments)은 샌드박스 종목을 계속 보여줘야 한다
	// (031 SANDBOX-001). 위 메서드로 합치면 튜토리얼 진입 자체가 막힌다.
	@Test
	void getInstrumentEntitiesStillIncludesTutorialSampleInstruments() {
		InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
		Instrument sandbox = Instrument.create(
			Market.STOCK, "SANDBOX_STK_1", "알파전자", BigDecimal.valueOf(100), 10000L, true, LocalDateTime.now());
		ReflectionTestUtils.setField(sandbox, "tutorialSample", true);
		when(instrumentRepository.findByMarketOrderByIdAsc(Market.STOCK)).thenReturn(List.of(sandbox));
		InstrumentService instrumentService = new InstrumentService(instrumentRepository);

		List<Instrument> result = instrumentService.getInstrumentEntities(Market.STOCK);

		assertThat(result).extracting(Instrument::getSymbol).containsExactly("SANDBOX_STK_1");
		verify(instrumentRepository).findByMarketOrderByIdAsc(Market.STOCK);
		verifyNoMoreInteractions(instrumentRepository);
	}
}
