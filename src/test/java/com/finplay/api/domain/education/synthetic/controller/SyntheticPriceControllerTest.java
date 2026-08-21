// 튜토리얼 합성 시세 조회 API의 인증, 검증, 응답과 오류 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.domain.education.synthetic.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.education.synthetic.dto.response.SyntheticPriceSeriesResponse;
import com.finplay.api.domain.education.synthetic.service.SyntheticPriceService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SyntheticPriceController.class)
@Import(SecurityConfig.class)
class SyntheticPriceControllerTest {

	private static final String TOKEN = "access-token";
	private static final long USER_ID = 7L;

	@Autowired
	private MockMvc mockMvc;
	@MockitoBean
	private SyntheticPriceService syntheticPriceService;
	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void getSyntheticPricesReturnsTitleTickSecondsAndHundredPrices() throws Exception {
		authenticate();
		List<BigDecimal> prices = IntStream.range(0, 100)
			.mapToObj(i -> BigDecimal.valueOf(10_000 + i))
			.collect(Collectors.toList());
		when(syntheticPriceService.generateSeries(10L))
			.thenReturn(new SyntheticPriceSeriesResponse("삼성전자", 3, prices));

		mockMvc.perform(get("/api/education/practice/synthetic-prices/10")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("삼성전자"))
			.andExpect(jsonPath("$.tickSeconds").value(3))
			.andExpect(jsonPath("$.prices.length()").value(100))
			.andExpect(jsonPath("$.prices[0]").value(10000))
			.andExpect(jsonPath("$.prices[99]").value(10099));
	}

	@Test
	void getSyntheticPricesRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(get("/api/education/practice/synthetic-prices/10"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(syntheticPriceService);
	}

	@Test
	void getSyntheticPricesRejectsNonPositiveInstrumentId() throws Exception {
		authenticate();
		mockMvc.perform(get("/api/education/practice/synthetic-prices/0")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(syntheticPriceService);
	}

	@Test
	void getSyntheticPricesMapsMissingInstrumentToNotFound() throws Exception {
		authenticate();
		when(syntheticPriceService.generateSeries(10L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(get("/api/education/practice/synthetic-prices/10")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(TOKEN)).thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}
}
