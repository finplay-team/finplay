// 실제 MySQL·Redis·인증 필터·MockMvc 비동기 SSE 경로를 통해 /api/cryptos/stream 구독 시 snapshot이 먼저,
// 이후 CryptoPriceUpdatedEvent 발생 시 price가, PriceStore.saveConnectionStatus 변경 시 status가 도착하는지
// 검증하는 통합 테스트다 (ADR-0018, tasks.md 항목 1 핵심 시나리오).
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// SseEmitter는 완료되지 않는 한 MockMvc의 asyncDispatch가 발생하지 않는다 — MvcResult.getAsyncResult()는 내부적으로
// 그 디스패치를 spring.mvc.async.request-timeout=-1 설정 그대로 무한정 기다리므로 이 클래스에서는 절대 호출하지 않는다.
// 혹시 모를 회귀에 대비해 테스트 타임아웃도 걸어둔다 (StockPriceStreamIntegrationTest와 동일 패턴).
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Timeout(60)
class CryptoPriceStreamIntegrationTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 10);
	private static final LocalDateTime BASELINE_TIME = LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 0));

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private CryptoPriceStreamService cryptoPriceStreamService;

	@Autowired
	private TestClock clock;

	@Test
	void subscriptionReceivesSnapshotThenPriceOnCryptoPriceUpdatedEventThenStatusOnConnectionStatusChange()
		throws Exception {
		clock.set(BASELINE_TIME);
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail(), "password-hash", uniqueNickname(), LocalDateTime.now()));
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();
		String symbol = "SSEFLOW" + UUID.randomUUID().toString().substring(0, 6);

		MvcResult subscribeResult = mockMvc.perform(get("/api/cryptos/stream")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(request().asyncStarted())
			.andReturn();
		// SseEmitter는 이 테스트에서 의도적으로 완료시키지 않는 한 asyncDispatch가 발생하지 않는다.
		// MvcResult.getAsyncResult()는 그 디스패치를 spring.mvc.async.request-timeout=-1 설정 그대로
		// 무한정(Long.MAX_VALUE ms) 기다리므로 여기서는 절대 호출하지 않는다 — 대신 응답에 이미 누적된
		// content를 직접 읽는다 (StockPriceStreamIntegrationTest와 동일 접근).
		String contentAfterSubscribe = subscribeResult.getResponse().getContentAsString();
		assertThat(contentAfterSubscribe).contains("event:snapshot");
		assertThat(contentAfterSubscribe).doesNotContain("event:price");
		assertThat(contentAfterSubscribe).doesNotContain("event:status");

		// PriceStore.saveTick()이 CryptoPriceUpdatedEvent를 동기로 publish하므로(별도 트랜잭션 커밋 대기 없음),
		// 이 호출이 끝나면 이미 CryptoPriceStreamService.onPriceUpdated()의 emitter 전송까지 끝나 있다.
		LocalDateTime receivedAt = LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 5, 33));
		priceStore.saveTick(symbol, new BigDecimal("140000000"), receivedAt);

		String contentAfterPrice = subscribeResult.getResponse().getContentAsString();
		int snapshotIndex = contentAfterPrice.indexOf("event:snapshot");
		int priceIndex = contentAfterPrice.indexOf("event:price");
		assertThat(snapshotIndex).isGreaterThanOrEqualTo(0);
		assertThat(priceIndex).isGreaterThan(snapshotIndex);
		assertThat(contentAfterPrice).contains("\"symbol\":\"" + symbol + "\"");
		// id는 receivedAt(체결 시각, 09:05:33)이 아니라 observedAt(관측 시각) 기준이다 — saveTick이 그 값을
		// LocalDateTime.now(clock)으로 채우고, 이 테스트의 TestClock은 BASELINE_TIME(09:00:00)에 고정돼 있다
		// (034-crypto-price-rest-backup, CryptoPriceStreamService id 충돌 회귀 수정).
		assertThat(contentAfterPrice).contains(
			"id:CRYPTO:" + symbol + ":" + SERVICE_DATE.toString().replace("-", "") + "090000");
		assertThat(contentAfterPrice).doesNotContain("event:status");

		// 실서비스에서는 5초 주기 @Scheduled가 이 변경을 감지하지만, 그 주기를 실제로 기다리면 테스트가
		// 느려지고 같은 컨텍스트를 공유하는 다른 테스트의 배경 스케줄 실행과 경합할 수 있어(공유 싱글턴 빈)
		// StockPriceStreamIntegrationTest가 publishScheduledUpdates()를 직접 호출하는 것과 동일하게 이
		// 스케줄 메서드를 직접 호출해 결정적으로 검증한다. 현재 캐시된 상태(lastKnownConnectionStatus)와
		// 다른 값으로 Redis를 갱신해야 "변경"으로 인식된다.
		FeedConnectionStatus cachedStatus = (FeedConnectionStatus)ReflectionTestUtils
			.getField(cryptoPriceStreamService, "lastKnownConnectionStatus");
		FeedConnectionStatus newStatus = cachedStatus == FeedConnectionStatus.CONNECTED
			? FeedConnectionStatus.DISCONNECTED : FeedConnectionStatus.CONNECTED;
		priceStore.saveConnectionStatus(newStatus);
		cryptoPriceStreamService.publishConnectionStatusIfChanged();

		String contentAfterStatus = subscribeResult.getResponse().getContentAsString();
		assertThat(contentAfterStatus).contains("event:status");
		String expectedPriceStatus = newStatus == FeedConnectionStatus.CONNECTED ? "AVAILABLE" : "UNAVAILABLE";
		assertThat(contentAfterStatus).contains("\"status\":\"" + expectedPriceStatus + "\"");
		assertThat(contentAfterStatus).contains("\"reason\":\"" + newStatus.name() + "\"");
	}

	private static String uniqueEmail() {
		return "sse-crypto-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname() {
		return "sse-crypto-" + UUID.randomUUID().toString().replace("-", "");
	}
}
