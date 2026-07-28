// 종목 목록 API의 시장 필터링, 공개 접근, 검증 실패, 응답 DTO 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.market.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.response.InstrumentResponse;
import com.finplay.api.market.service.InstrumentService;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InstrumentController.class)
@Import(SecurityConfig.class)
class InstrumentControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private InstrumentService instrumentService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void getInstrumentsReturnsAllTwentyEightWithoutAuthenticationWhenMarketParamOmitted() throws Exception {
		when(instrumentService.getInstruments(null))
			.thenReturn(instruments("STOCK", 16));

		mockMvc.perform(get("/api/instruments"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(16))
			.andExpect(jsonPath("$[0].instrumentId").value(1))
			.andExpect(jsonPath("$[0].market").value("STOCK"))
			.andExpect(jsonPath("$[0].symbol").value("SYM1"))
			.andExpect(jsonPath("$[0].name").value("종목1"))
			.andExpect(jsonPath("$[0].tickSize").value(100))
			.andExpect(jsonPath("$[0].minOrderAmount").value(70000))
			.andExpect(jsonPath("$[0].tradable").value(true));

		verify(instrumentService).getInstruments(null);
		verifyNoInteractions(jwtTokenProvider);
	}

	@Test
	void getInstrumentsReturnsOnlySixteenWhenMarketIsStock() throws Exception {
		when(instrumentService.getInstruments(Market.STOCK))
			.thenReturn(instruments("STOCK", 16));

		mockMvc.perform(get("/api/instruments").param("market", "STOCK"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(16))
			.andExpect(jsonPath("$[0].market").value("STOCK"))
			.andExpect(jsonPath("$[15].market").value("STOCK"));

		verify(instrumentService).getInstruments(Market.STOCK);
		verifyNoInteractions(jwtTokenProvider);
	}

	@Test
	void getInstrumentsReturnsOnlyTwelveWhenMarketIsCrypto() throws Exception {
		when(instrumentService.getInstruments(Market.CRYPTO))
			.thenReturn(instruments("CRYPTO", 12));

		mockMvc.perform(get("/api/instruments").param("market", "CRYPTO"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(12))
			.andExpect(jsonPath("$[0].market").value("CRYPTO"))
			.andExpect(jsonPath("$[11].market").value("CRYPTO"));

		verify(instrumentService).getInstruments(Market.CRYPTO);
		verifyNoInteractions(jwtTokenProvider);
	}

	@Test
	void getInstrumentsReturnsCommonValidationErrorForUnknownMarketValueWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/instruments").param("market", "FOO"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("market은 STOCK 또는 CRYPTO만 가능합니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(instrumentService);
	}

	@Test
	void getInstrumentsResponseExposesOnlyDtoFieldsNotEntityInternals() throws Exception {
		when(instrumentService.getInstruments(null)).thenReturn(List.of(
			new InstrumentResponse(1L, "STOCK", "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true)));

		mockMvc.perform(get("/api/instruments"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].instrumentId").exists())
			.andExpect(jsonPath("$[0].market").exists())
			.andExpect(jsonPath("$[0].symbol").exists())
			.andExpect(jsonPath("$[0].name").exists())
			.andExpect(jsonPath("$[0].tickSize").exists())
			.andExpect(jsonPath("$[0].minOrderAmount").exists())
			.andExpect(jsonPath("$[0].tradable").exists())
			.andExpect(jsonPath("$[0].id").doesNotExist())
			.andExpect(jsonPath("$[0].createdAt").doesNotExist());
	}

	private static List<InstrumentResponse> instruments(String market, int count) {
		return IntStream.range(0, count)
			.mapToObj(i -> new InstrumentResponse(
				(long)(i + 1),
				market,
				"SYM" + (i + 1),
				"종목" + (i + 1),
				BigDecimal.valueOf(100),
				70000L,
				true))
			.toList();
	}
}
