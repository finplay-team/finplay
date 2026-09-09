package com.finplay.api.domain.watchlist.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.watchlist.dto.response.WatchlistItemListResponse;
import com.finplay.api.domain.watchlist.dto.response.WatchlistItemResponse;
import com.finplay.api.domain.watchlist.service.WatchlistService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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

@WebMvcTest(WatchlistController.class)
@Import(SecurityConfig.class)
class WatchlistControllerTest {

	private static final String TOKEN = "access-token";
	private static final long USER_ID = 7L;

	@Autowired
	private MockMvc mockMvc;
	@MockitoBean
	private WatchlistService watchlistService;
	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void createWatchlistItemReturnsCreatedWithEveryField() throws Exception {
		authenticate();
		when(watchlistService.createWatchlistItem(USER_ID, 10L)).thenReturn(new WatchlistItemResponse(
			99L, 10L, "STOCK", "005930", "삼성전자", LocalDateTime.of(2026, 8, 3, 10, 0)));

		mockMvc.perform(post("/api/watchlist-items").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"instrumentId\":10}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.watchlistItemId").value(99))
			.andExpect(jsonPath("$.instrumentId").value(10))
			.andExpect(jsonPath("$.market").value("STOCK"))
			.andExpect(jsonPath("$.symbol").value("005930"))
			.andExpect(jsonPath("$.name").value("삼성전자"))
			.andExpect(jsonPath("$.createdAt").value("2026-08-03T10:00:00"));
	}

	@Test
	void createWatchlistItemRejectsMissingInstrumentId() throws Exception {
		authenticate();
		mockMvc.perform(post("/api/watchlist-items").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(watchlistService);
	}

	@Test
	void createWatchlistItemRejectsNonPositiveInstrumentId() throws Exception {
		authenticate();
		mockMvc.perform(post("/api/watchlist-items").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"instrumentId\":0}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(watchlistService);
	}

	@Test
	void createWatchlistItemRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(post("/api/watchlist-items").contentType(MediaType.APPLICATION_JSON)
			.content("{\"instrumentId\":10}"))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(watchlistService);
	}

	@Test
	void createWatchlistItemMapsMissingInstrumentToNotFound() throws Exception {
		authenticate();
		when(watchlistService.createWatchlistItem(USER_ID, 10L))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(post("/api/watchlist-items").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"instrumentId\":10}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void createWatchlistItemMapsDuplicateToConflict() throws Exception {
		authenticate();
		when(watchlistService.createWatchlistItem(USER_ID, 10L))
			.thenThrow(new BusinessException(ErrorCode.DUPLICATE_RESOURCE));

		mockMvc.perform(post("/api/watchlist-items").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"instrumentId\":10}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"));
	}

	@Test
	void getWatchlistItemsReturnsEveryActualField() throws Exception {
		authenticate();
		when(watchlistService.getWatchlistItems(USER_ID, null)).thenReturn(new WatchlistItemListResponse(List.of(
			new WatchlistItemResponse(99L, 10L, "STOCK", "005930", "삼성전자",
				LocalDateTime.of(2026, 8, 3, 10, 0)))));

		mockMvc.perform(get("/api/watchlist-items").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].watchlistItemId").value(99))
			.andExpect(jsonPath("$.content[0].instrumentId").value(10))
			.andExpect(jsonPath("$.content[0].market").value("STOCK"))
			.andExpect(jsonPath("$.content[0].symbol").value("005930"))
			.andExpect(jsonPath("$.content[0].name").value("삼성전자"))
			.andExpect(jsonPath("$.content[0].createdAt").value("2026-08-03T10:00:00"));
	}

	@Test
	void getWatchlistItemsFiltersByMarket() throws Exception {
		authenticate();
		when(watchlistService.getWatchlistItems(USER_ID, Market.CRYPTO)).thenReturn(new WatchlistItemListResponse(
			List.of(new WatchlistItemResponse(100L, 20L, "CRYPTO", "BTC", "비트코인",
				LocalDateTime.of(2026, 8, 3, 11, 0)))));

		mockMvc.perform(get("/api/watchlist-items").param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].market").value("CRYPTO"));

		verify(watchlistService).getWatchlistItems(USER_ID, Market.CRYPTO);
	}

	@Test
	void getWatchlistItemsReturnsEmptyContent() throws Exception {
		authenticate();
		when(watchlistService.getWatchlistItems(USER_ID, null)).thenReturn(new WatchlistItemListResponse(List.of()));

		mockMvc.perform(get("/api/watchlist-items").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isArray())
			.andExpect(jsonPath("$.content").isEmpty());
	}

	@Test
	void getWatchlistItemsRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(get("/api/watchlist-items"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(watchlistService);
	}

	@Test
	void deleteWatchlistItemReturnsNoContentWithEmptyBody() throws Exception {
		authenticate();

		mockMvc.perform(delete("/api/watchlist-items/10")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		verify(watchlistService).deleteWatchlistItem(USER_ID, 10L);
	}

	@Test
	void deleteWatchlistItemRejectsZeroInstrumentId() throws Exception {
		assertInvalidDeletePath(0L);
	}

	@Test
	void deleteWatchlistItemRejectsNegativeInstrumentId() throws Exception {
		assertInvalidDeletePath(-1L);
	}

	@Test
	void deleteWatchlistItemRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(delete("/api/watchlist-items/10"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(watchlistService);
	}

	@Test
	void deleteWatchlistItemHidesMissingOrUnownedItemAsNotFound() throws Exception {
		authenticate();
		doThrow(new BusinessException(ErrorCode.WATCHLIST_ITEM_NOT_FOUND))
			.when(watchlistService).deleteWatchlistItem(USER_ID, 10L);

		mockMvc.perform(delete("/api/watchlist-items/10")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("WATCHLIST_ITEM_NOT_FOUND"));
	}

	private void assertInvalidDeletePath(long instrumentId) throws Exception {
		authenticate();
		mockMvc.perform(delete("/api/watchlist-items/{instrumentId}", instrumentId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(watchlistService);
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}
}
