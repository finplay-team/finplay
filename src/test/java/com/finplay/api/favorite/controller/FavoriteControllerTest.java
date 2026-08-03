// 즐겨찾기 등록 API의 인증, 검증, 응답과 오류 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.favorite.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.favorite.dto.response.FavoriteListResponse;
import com.finplay.api.favorite.dto.response.FavoriteResponse;
import com.finplay.api.favorite.service.FavoriteService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FavoriteController.class)
@Import(SecurityConfig.class)
class FavoriteControllerTest {

	private static final String TOKEN = "access-token";
	private static final long USER_ID = 7L;

	@Autowired
	private MockMvc mockMvc;
	@MockitoBean
	private FavoriteService favoriteService;
	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void getFavoritesReturnsEveryActualField() throws Exception {
		authenticate();
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(
			new FavoriteResponse(99L, 10L, "STOCK", "005930", "삼성전자",
				LocalDateTime.of(2026, 8, 3, 10, 0)))));

		mockMvc.perform(get("/api/favorites").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].favoriteId").value(99))
			.andExpect(jsonPath("$.content[0].instrumentId").value(10))
			.andExpect(jsonPath("$.content[0].market").value("STOCK"))
			.andExpect(jsonPath("$.content[0].symbol").value("005930"))
			.andExpect(jsonPath("$.content[0].name").value("삼성전자"))
			.andExpect(jsonPath("$.content[0].createdAt").value("2026-08-03T10:00:00"));
	}

	@Test
	void getFavoritesReturnsEmptyContent() throws Exception {
		authenticate();
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of()));

		mockMvc.perform(get("/api/favorites").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isArray())
			.andExpect(jsonPath("$.content").isEmpty());
	}

	@Test
	void getFavoritesRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(get("/api/favorites"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(favoriteService);
	}

	@Test
	void createFavoriteReturnsCreatedWithEveryField() throws Exception {
		authenticate();
		when(favoriteService.createFavorite(USER_ID, 10L)).thenReturn(new FavoriteResponse(
			99L, 10L, "STOCK", "005930", "삼성전자", LocalDateTime.of(2026, 8, 3, 10, 0)));

		mockMvc.perform(post("/api/favorites").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"instrumentId\":10}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.favoriteId").value(99))
			.andExpect(jsonPath("$.instrumentId").value(10))
			.andExpect(jsonPath("$.market").value("STOCK"))
			.andExpect(jsonPath("$.symbol").value("005930"))
			.andExpect(jsonPath("$.name").value("삼성전자"))
			.andExpect(jsonPath("$.createdAt").value("2026-08-03T10:00:00"));
	}

	@Test
	void createFavoriteRejectsMissingInstrumentId() throws Exception {
		authenticate();
		mockMvc.perform(post("/api/favorites").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(favoriteService);
	}

	@Test
	void createFavoriteRejectsNonPositiveInstrumentId() throws Exception {
		authenticate();
		mockMvc.perform(post("/api/favorites").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"instrumentId\":0}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(favoriteService);
	}

	@Test
	void createFavoriteRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(post("/api/favorites").contentType(MediaType.APPLICATION_JSON)
			.content("{\"instrumentId\":10}"))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(favoriteService);
	}

	@Test
	void createFavoriteMapsMissingInstrumentToNotFound() throws Exception {
		assertBusinessError(ErrorCode.NOT_FOUND, "NOT_FOUND", 404);
	}

	@Test
	void createFavoriteMapsDuplicateToConflict() throws Exception {
		assertBusinessError(ErrorCode.DUPLICATE_RESOURCE, "DUPLICATE_RESOURCE", 409);
	}

	@Test
	void createFavoriteMapsNonTradableInstrumentToConflict() throws Exception {
		assertBusinessError(ErrorCode.INSTRUMENT_NOT_TRADABLE, "INSTRUMENT_NOT_TRADABLE", 409);
	}

	@Test
	void deleteFavoriteReturnsNoContentWithEmptyBody() throws Exception {
		authenticate();

		mockMvc.perform(delete("/api/favorites/10")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		org.mockito.Mockito.verify(favoriteService).deleteFavorite(USER_ID, 10L);
	}

	@Test
	void deleteFavoriteRejectsZeroInstrumentId() throws Exception {
		assertInvalidDeletePath(0L);
	}

	@Test
	void deleteFavoriteRejectsNegativeInstrumentId() throws Exception {
		assertInvalidDeletePath(-1L);
	}

	@Test
	void deleteFavoriteRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(delete("/api/favorites/10"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(favoriteService);
	}

	@Test
	void deleteFavoriteHidesMissingOrUnownedFavoriteAsNotFound() throws Exception {
		authenticate();
		doThrow(new BusinessException(ErrorCode.FAVORITE_NOT_FOUND))
			.when(favoriteService).deleteFavorite(USER_ID, 10L);

		mockMvc.perform(delete("/api/favorites/10")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("FAVORITE_NOT_FOUND"));
	}

	private void assertInvalidDeletePath(long instrumentId) throws Exception {
		authenticate();
		mockMvc.perform(delete("/api/favorites/{instrumentId}", instrumentId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(favoriteService);
	}

	private void assertBusinessError(ErrorCode errorCode, String code, int status) throws Exception {
		authenticate();
		when(favoriteService.createFavorite(USER_ID, 10L)).thenThrow(new BusinessException(errorCode));
		mockMvc.perform(post("/api/favorites").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"instrumentId\":10}"))
			.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(TOKEN)).thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}
}
