// mock Redis(StringRedisTemplate)로 PriceStore.getLatestPrices의 배치화(전역 연결상태 1회 조회)를 검증하는 단위 테스트다.
package com.finplay.api.domain.market.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.market.event.CryptoPriceUpdatedEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

class PriceStoreTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 30, 12, 0, 0);
	private static final Clock FIXED_CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
	private static final String STATUS_KEY = "feed:crypto:status";

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
	@SuppressWarnings("unchecked")
	private final HashOperations<String, String, String> hashOperations = mock(HashOperations.class);
	@SuppressWarnings("unchecked")
	private final ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);
	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

	private PriceStore priceStore() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
		return spy(new PriceStore(redisTemplate, FIXED_CLOCK, eventPublisher));
	}

	private void stubTick(String symbol, BigDecimal price, LocalDateTime receivedAt) {
		when(hashOperations.get("price:crypto:" + symbol, "price")).thenReturn(price.toPlainString());
		when(hashOperations.get("price:crypto:" + symbol, "receivedAt")).thenReturn(receivedAt.toString());
	}

	@Test
	void getLatestPricesQueriesConnectionStatusOnlyOnceForMultipleSymbols() {
		PriceStore priceStore = priceStore();
		when(valueOperations.get(STATUS_KEY)).thenReturn(FeedConnectionStatus.CONNECTED.name());
		stubTick("BTC", new BigDecimal("50000000"), NOW.minusSeconds(2));
		stubTick("ETH", new BigDecimal("3000000"), NOW.minusSeconds(2));
		stubTick("XRP", new BigDecimal("700"), NOW.minusSeconds(2));

		Map<String, CryptoPriceDto> result = priceStore.getLatestPrices(List.of("BTC", "ETH", "XRP"));

		assertThat(result).hasSize(3);
		assertThat(result.get("BTC").price()).isEqualByComparingTo("50000000");
		assertThat(result.get("ETH").price()).isEqualByComparingTo("3000000");
		assertThat(result.get("XRP").price()).isEqualByComparingTo("700");
		// 종목(심볼) 수(3개)와 무관하게 전역 연결상태 조회(getConnectionStatus)는 요청당 1회만 일어나야 한다
		// (PR #97 리뷰 권장사항 — 개별 isPriceAvailable/getLatestPrice 반복호출 시 발생하던 중복 제거).
		verify(priceStore, times(1)).getConnectionStatus();
		verify(valueOperations, times(1)).get(STATUS_KEY);
	}

	@Test
	void getLatestPricesReturnsEmptyMapImmediatelyWithoutPerSymbolLookupWhenDisconnected() {
		PriceStore priceStore = priceStore();
		when(valueOperations.get(STATUS_KEY)).thenReturn(FeedConnectionStatus.DISCONNECTED.name());

		Map<String, CryptoPriceDto> result = priceStore.getLatestPrices(List.of("BTC", "ETH"));

		assertThat(result).isEmpty();
		verify(priceStore, never()).getLatestPrice(any());
		verify(hashOperations, never()).get(any(), any());
	}

	@Test
	void getLatestPricesExcludesOnlyStaleSymbolsInMixedFreshnessBatch() {
		PriceStore priceStore = priceStore();
		when(valueOperations.get(STATUS_KEY)).thenReturn(FeedConnectionStatus.CONNECTED.name());
		stubTick("FRESH", new BigDecimal("100"), NOW.minusSeconds(2));
		stubTick("STALE", new BigDecimal("200"), NOW.minusSeconds(11));

		Map<String, CryptoPriceDto> result = priceStore.getLatestPrices(List.of("FRESH", "STALE"));

		assertThat(result).hasSize(1);
		assertThat(result).containsKey("FRESH");
		assertThat(result).doesNotContainKey("STALE");
	}

	@Test
	void getLatestPricesOmitsSymbolWithNoSavedTickFromResultMap() {
		PriceStore priceStore = priceStore();
		when(valueOperations.get(STATUS_KEY)).thenReturn(FeedConnectionStatus.CONNECTED.name());
		when(hashOperations.get(eq("price:crypto:UNKNOWN"), any())).thenReturn(null);

		Map<String, CryptoPriceDto> result = priceStore.getLatestPrices(List.of("UNKNOWN"));

		assertThat(result).isEmpty();
	}

	// 아래부터는 015-limit-order LMT-002 트리거(CryptoPriceUpdatedEvent publish) 검증이다.

	@Test
	void saveTickPublishesEventWhenNoExistingTickForSymbol() {
		PriceStore priceStore = priceStore();
		when(hashOperations.get("price:crypto:NEW_SYMBOL", "receivedAt")).thenReturn(null);
		LocalDateTime receivedAt = NOW.minusSeconds(1);

		priceStore.saveTick("NEW_SYMBOL", new BigDecimal("100"), receivedAt);

		ArgumentCaptor<CryptoPriceUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(CryptoPriceUpdatedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		CryptoPriceUpdatedEvent event = eventCaptor.getValue();
		assertThat(event.symbol()).isEqualTo("NEW_SYMBOL");
		assertThat(event.price()).isEqualByComparingTo("100");
		assertThat(event.receivedAt()).isEqualTo(receivedAt);
	}

	@Test
	void saveTickPublishesEventWhenNewerTickReplacesExistingValue() {
		PriceStore priceStore = priceStore();
		LocalDateTime existing = NOW.minusSeconds(5);
		LocalDateTime newer = NOW.minusSeconds(1);
		stubTick("REPLACE", new BigDecimal("100"), existing);

		priceStore.saveTick("REPLACE", new BigDecimal("200"), newer);

		verify(eventPublisher).publishEvent(any(CryptoPriceUpdatedEvent.class));
	}

	@Test
	void saveTickDoesNotPublishEventWhenTickIsOlderThanExistingValue() {
		PriceStore priceStore = priceStore();
		LocalDateTime existing = NOW.minusSeconds(1);
		LocalDateTime older = existing.minusSeconds(5);
		stubTick("OLDER", new BigDecimal("100"), existing);

		priceStore.saveTick("OLDER", new BigDecimal("999"), older);

		verify(eventPublisher, never()).publishEvent(any());
		verify(hashOperations, never()).putAll(eq("price:crypto:OLDER"), any());
	}

	@Test
	void saveTickDoesNotPublishEventWhenTickIsSameInstantAsExistingValue() {
		PriceStore priceStore = priceStore();
		LocalDateTime same = NOW.minusSeconds(1);
		stubTick("SAME_INSTANT", new BigDecimal("100"), same);

		priceStore.saveTick("SAME_INSTANT", new BigDecimal("999"), same);

		verify(eventPublisher, never()).publishEvent(any());
		verify(hashOperations, never()).putAll(eq("price:crypto:SAME_INSTANT"), any());
	}

	@Test
	void saveTickPublishesOneEventPerSymbolWithItsOwnPayloadWhenMultipleSymbolsUpdateTogether() {
		PriceStore priceStore = priceStore();
		when(hashOperations.get("price:crypto:BTC", "receivedAt")).thenReturn(null);
		when(hashOperations.get("price:crypto:ETH", "receivedAt")).thenReturn(null);
		LocalDateTime btcReceivedAt = NOW.minusSeconds(2);
		LocalDateTime ethReceivedAt = NOW.minusSeconds(1);

		priceStore.saveTick("BTC", new BigDecimal("50000000"), btcReceivedAt);
		priceStore.saveTick("ETH", new BigDecimal("3000000"), ethReceivedAt);

		ArgumentCaptor<CryptoPriceUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(CryptoPriceUpdatedEvent.class);
		verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
		List<CryptoPriceUpdatedEvent> events = eventCaptor.getAllValues();

		CryptoPriceUpdatedEvent btcEvent = events.stream().filter(e -> e.symbol().equals("BTC")).findFirst()
			.orElseThrow();
		assertThat(btcEvent.price()).isEqualByComparingTo("50000000");
		assertThat(btcEvent.receivedAt()).isEqualTo(btcReceivedAt);

		CryptoPriceUpdatedEvent ethEvent = events.stream().filter(e -> e.symbol().equals("ETH")).findFirst()
			.orElseThrow();
		assertThat(ethEvent.price()).isEqualByComparingTo("3000000");
		assertThat(ethEvent.receivedAt()).isEqualTo(ethReceivedAt);
	}

	// 아래부터는 034-crypto-price-rest-backup PRICE-REST-001 — observedAt(관측 시각) 분리 검증이다.
	// recordObservation의 이벤트 발행 조건(가격 변경 시에만 publish)은 tasks.md 항목 3에서 고정한다 —
	// 아래 §recordObservation 이벤트 발행 조건 절에서 검증한다.

	@Test
	void recordObservationUpdatesObservedAtOnlyWhenPriceUnchanged() {
		PriceStore priceStore = priceStore();
		// 이 심볼은 이미 최소 한 번 관측된 적이 있다(receivedAt 존재) — 부트스트랩 대상이 아니다.
		when(hashOperations.get("price:crypto:OBS_SAME", "price")).thenReturn("100");
		when(hashOperations.get("price:crypto:OBS_SAME", "receivedAt")).thenReturn(NOW.minusSeconds(5).toString());

		priceStore.recordObservation("OBS_SAME", new BigDecimal("100"), NOW);

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor
			.forClass(Map.class);
		verify(hashOperations).putAll(eq("price:crypto:OBS_SAME"), fieldsCaptor.capture());
		Map<String, String> fields = fieldsCaptor.getValue();
		assertThat(fields).containsEntry("observedAt", NOW.toString());
		assertThat(fields).doesNotContainKey("price");
		assertThat(fields).doesNotContainKey("receivedAt");
	}

	@Test
	void recordObservationUpdatesPriceWhenDifferentButLeavesReceivedAtFieldUntouched() {
		PriceStore priceStore = priceStore();
		// 이 심볼도 이미 최소 한 번 관측된 적이 있다(receivedAt 존재) — 부트스트랩 대상이 아니다.
		when(hashOperations.get("price:crypto:OBS_DIFF", "price")).thenReturn("100");
		when(hashOperations.get("price:crypto:OBS_DIFF", "receivedAt")).thenReturn(NOW.minusSeconds(5).toString());

		priceStore.recordObservation("OBS_DIFF", new BigDecimal("150"), NOW);

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor
			.forClass(Map.class);
		verify(hashOperations).putAll(eq("price:crypto:OBS_DIFF"), fieldsCaptor.capture());
		Map<String, String> fields = fieldsCaptor.getValue();
		assertThat(fields).containsEntry("observedAt", NOW.toString());
		assertThat(fields).containsEntry("price", "150");
		assertThat(fields).doesNotContainKey("receivedAt");
	}

	@Test
	void isStaleIsFalseWhenObservedAtIsFreshEvenIfReceivedAtIsVeryOld() {
		PriceStore priceStore = priceStore();
		LocalDateTime oldReceivedAt = NOW.minusMinutes(5);
		LocalDateTime freshObservedAt = NOW.minusSeconds(1);
		when(hashOperations.get("price:crypto:OBS_FRESH", "price")).thenReturn("100");
		when(hashOperations.get("price:crypto:OBS_FRESH", "receivedAt")).thenReturn(oldReceivedAt.toString());
		when(hashOperations.get("price:crypto:OBS_FRESH", "observedAt")).thenReturn(freshObservedAt.toString());

		CryptoPriceDto result = priceStore.getLatestPrice("OBS_FRESH").orElseThrow();

		assertThat(result.receivedAt()).isEqualTo(oldReceivedAt);
		assertThat(result.observedAt()).isEqualTo(freshObservedAt);
		assertThat(priceStore.isStale(result.observedAt())).isFalse();
	}

	@Test
	void getLatestPriceFallsBackToReceivedAtWhenObservedAtFieldIsMissingFromExistingHash() {
		PriceStore priceStore = priceStore();
		LocalDateTime receivedAt = NOW.minusSeconds(3);
		// 배포 전 기존 해시를 재현 — observedAt 필드가 아예 없다(hashOperations.get이 null 반환).
		when(hashOperations.get("price:crypto:OBS_LEGACY", "price")).thenReturn("100");
		when(hashOperations.get("price:crypto:OBS_LEGACY", "receivedAt")).thenReturn(receivedAt.toString());
		when(hashOperations.get("price:crypto:OBS_LEGACY", "observedAt")).thenReturn(null);

		CryptoPriceDto result = priceStore.getLatestPrice("OBS_LEGACY").orElseThrow();

		assertThat(result.observedAt()).isEqualTo(receivedAt);
	}

	// PRICE-REST-003 — 이 spec에서 가장 깨지기 쉬운 지점. REST 폴러가 recordObservation으로 observedAt="지금"을
	// 남긴 직후, 그보다 이른 receivedAt(실제 체결 시각)을 가진 진짜 웹소켓 틱이 도착해도 saveTick의 MKT-003
	// 가드는 receivedAt끼리만 비교하므로 그 틱이 버려지지 않고 정상 반영돼야 한다.
	@Test
	void saveTickStillAppliesTickWithReceivedAtEarlierThanJustRecordedObservationTime() {
		PriceStore priceStore = priceStore();
		LocalDateTime oldReceivedAt = NOW.minusSeconds(20);
		stubTick("OBS_RACE", new BigDecimal("100"), oldReceivedAt);

		// REST 폴러가 "지금"(observedAt=NOW)을 관측 시각으로 기록한다 — receivedAt은 건드리지 않는다.
		priceStore.recordObservation("OBS_RACE", new BigDecimal("100"), NOW);

		// 실제 체결 틱 도착 — receivedAt은 oldReceivedAt(-20초)보다 늦고 NOW(관측 시각)보다는 이르다.
		LocalDateTime tradeReceivedAt = NOW.minusSeconds(15);
		priceStore.saveTick("OBS_RACE", new BigDecimal("101"), tradeReceivedAt);

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor
			.forClass(Map.class);
		verify(hashOperations, atLeastOnce()).putAll(eq("price:crypto:OBS_RACE"), fieldsCaptor.capture());
		Map<String, String> lastFields = fieldsCaptor.getAllValues().get(fieldsCaptor.getAllValues().size() - 1);
		assertThat(lastFields.get("price")).isEqualTo("101");
		assertThat(lastFields.get("receivedAt")).isEqualTo(tradeReceivedAt.toString());
	}

	// 034-crypto-price-rest-backup tasks.md 항목 3 — recordObservation의 이벤트 발행 조건. 가격이 실제로
	// 바뀌었을 때만 CryptoPriceUpdatedEvent를 publish한다(관측 시각만 갱신된 경우는 미발행) — 같은 값으로
	// LimitOrderTriggerListener를 3초마다 다시 도는 것은 순수한 낭비이기 때문이다
	// (plan.md "컴포넌트 설계 — PriceStore" §이벤트 발행).

	@Test
	void recordObservationDoesNotPublishEventWhenOnlyObservedAtChanges() {
		PriceStore priceStore = priceStore();
		LocalDateTime existingReceivedAt = NOW.minusSeconds(5);
		stubTick("OBS_EVENT_SAME", new BigDecimal("100"), existingReceivedAt);

		priceStore.recordObservation("OBS_EVENT_SAME", new BigDecimal("100"), NOW);

		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void recordObservationPublishesEventWithExistingReceivedAtWhenPriceActuallyChanges() {
		PriceStore priceStore = priceStore();
		LocalDateTime existingReceivedAt = NOW.minusSeconds(5);
		stubTick("OBS_EVENT_DIFF", new BigDecimal("100"), existingReceivedAt);

		priceStore.recordObservation("OBS_EVENT_DIFF", new BigDecimal("150"), NOW);

		ArgumentCaptor<CryptoPriceUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(CryptoPriceUpdatedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		CryptoPriceUpdatedEvent event = eventCaptor.getValue();
		assertThat(event.symbol()).isEqualTo("OBS_EVENT_DIFF");
		assertThat(event.price()).isEqualByComparingTo("150");
		// receivedAt(체결 시각)은 recordObservation이 절대 건드리지 않으므로 기존 값 그대로 실려야 한다.
		assertThat(event.receivedAt()).isEqualTo(existingReceivedAt);
	}

	// 아래부터는 PR 리뷰 [권장] 후속 수정 — recordObservation이 "receivedAt이 아예 없던 심볼"(웹소켓 체결을
	// 한 번도 받은 적 없는 상태, 예: 서버 재시작 직후 REST가 그 심볼의 첫 웹소켓 체결보다 먼저 도착)에도
	// receivedAt을 영원히 비워둬 getLatestPrice가 계속 UNAVAILABLE을 돌려주던 문제를 부트스트랩으로 고친다.

	@Test
	void recordObservationBootstrapsReceivedAtAndPublishesEventWhenSymbolHasNeverBeenObserved() {
		PriceStore priceStore = priceStore();
		// 이 심볼은 웹소켓 체결이든 REST 관측이든 한 번도 기록된 적이 없다 — 두 필드 모두 null.
		when(hashOperations.get("price:crypto:OBS_BOOTSTRAP", "price")).thenReturn(null);
		when(hashOperations.get("price:crypto:OBS_BOOTSTRAP", "receivedAt")).thenReturn(null);

		priceStore.recordObservation("OBS_BOOTSTRAP", new BigDecimal("100"), NOW);

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor
			.forClass(Map.class);
		verify(hashOperations).putAll(eq("price:crypto:OBS_BOOTSTRAP"), fieldsCaptor.capture());
		Map<String, String> fields = fieldsCaptor.getValue();
		assertThat(fields).containsEntry("price", "100");
		assertThat(fields).containsEntry("observedAt", NOW.toString());
		// receivedAt이 없던 심볼만 observedAt과 같은 값으로 부트스트랩된다 — getLatestPrice가 더 이상
		// receivedAt 없음을 이유로 Optional.empty()를 돌려주지 않는다.
		assertThat(fields).containsEntry("receivedAt", NOW.toString());

		ArgumentCaptor<CryptoPriceUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(CryptoPriceUpdatedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		CryptoPriceUpdatedEvent event = eventCaptor.getValue();
		assertThat(event.symbol()).isEqualTo("OBS_BOOTSTRAP");
		assertThat(event.price()).isEqualByComparingTo("100");
		assertThat(event.receivedAt()).isEqualTo(NOW);
		assertThat(event.observedAt()).isEqualTo(NOW);
	}

	// 이미 저장값이 있는 심볼(웹소켓이든 REST든 한 번이라도 관측된 적 있음)에는 부트스트랩이 적용되지 않는다 —
	// receivedAt은 여전히 웹소켓만 갱신한다(PRICE-REST-003 무변경). recordObservationUpdatesObservedAtOnlyWhenPriceUnchanged·
	// recordObservationUpdatesPriceWhenDifferentButLeavesReceivedAtFieldUntouched(위)가 이미 receivedAt 스텁을
	// 갖춘 채 "receivedAt 필드를 건드리지 않음"을 검증하므로 이 케이스의 회귀도 함께 고정돼 있다.

	// 부트스트랩 이후 실제 웹소켓 체결이 들어오면 MKT-003 가드가 부트스트랩 시각을 "과거"로 보고 정상적으로
	// 덮어써야 한다 — 부트스트랩이 receivedAt을 영구히 얼려버리면 안 된다.
	@Test
	void saveTickOverwritesBootstrappedReceivedAtWhenRealTickArrivesLater() {
		PriceStore priceStore = priceStore();
		when(hashOperations.get("price:crypto:OBS_BOOTSTRAP_THEN_TICK", "price")).thenReturn(null);
		when(hashOperations.get("price:crypto:OBS_BOOTSTRAP_THEN_TICK", "receivedAt")).thenReturn(null);

		priceStore.recordObservation("OBS_BOOTSTRAP_THEN_TICK", new BigDecimal("100"), NOW);

		// 실제 Redis라면 위 호출로 receivedAt=NOW가 저장돼 있을 것이다 — mock은 putAll이 get 스텁을 자동으로
		// 갱신하지 않으므로, 이후 saveTick 호출부터 그 상태를 반영하도록 스텁을 갈아 끼운다.
		when(hashOperations.get("price:crypto:OBS_BOOTSTRAP_THEN_TICK", "receivedAt")).thenReturn(NOW.toString());

		// 실제 웹소켓 체결 — 부트스트랩 시각(NOW)보다 나중이다(서버 재시작 뒤 실제로 발생한 체결).
		LocalDateTime realReceivedAt = NOW.plusSeconds(2);
		priceStore.saveTick("OBS_BOOTSTRAP_THEN_TICK", new BigDecimal("105"), realReceivedAt);

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor
			.forClass(Map.class);
		verify(hashOperations, atLeastOnce()).putAll(eq("price:crypto:OBS_BOOTSTRAP_THEN_TICK"),
			fieldsCaptor.capture());
		Map<String, String> lastFields = fieldsCaptor.getAllValues().get(fieldsCaptor.getAllValues().size() - 1);
		assertThat(lastFields.get("price")).isEqualTo("105");
		assertThat(lastFields.get("receivedAt")).isEqualTo(realReceivedAt.toString());
	}

	// 아래부터는 spec 012 §코인 가격 스냅샷(이슈 #225) — recordSnapshot·getSnapshots 검증이다.

	@Test
	void recordSnapshotAddsMemberWithEpochMillisScoreAndPriceEncodedInMember() {
		PriceStore priceStore = priceStore();
		LocalDateTime recordedAt = NOW.minusMinutes(1);
		long expectedEpochMillis = recordedAt.atZone(ZoneOffset.UTC).toInstant().toEpochMilli();

		priceStore.recordSnapshot("BTC", recordedAt, new BigDecimal("50000000"), Duration.ofHours(24));

		verify(zSetOperations).add(
			"price:crypto:BTC:snapshots", expectedEpochMillis + ":50000000", expectedEpochMillis);
	}

	// 함정 검증 — recordSnapshot은 수익률이 아니라 "가격+시각"을 member 문자열에 그대로 담아야 한다. 수익률만
	// 저장하면 나중에 "N분 전 가격"을 복원할 수 없다(tasks.md 항목 1 함정).
	@Test
	void recordSnapshotEncodesRawPriceNotAReturnRatio() {
		PriceStore priceStore = priceStore();
		LocalDateTime recordedAt = NOW;
		long expectedEpochMillis = recordedAt.atZone(ZoneOffset.UTC).toInstant().toEpochMilli();

		priceStore.recordSnapshot("ETH", recordedAt, new BigDecimal("3000000.50"), Duration.ofHours(24));

		ArgumentCaptor<String> memberCaptor = ArgumentCaptor.forClass(String.class);
		verify(zSetOperations).add(eq("price:crypto:ETH:snapshots"), memberCaptor.capture(), anyDouble());
		String member = memberCaptor.getValue();
		String encodedPrice = member.substring(member.indexOf(':') + 1);
		assertThat(new BigDecimal(encodedPrice)).isEqualByComparingTo("3000000.50");
		assertThat(member).startsWith(expectedEpochMillis + ":");
	}

	// 가지치기 — retention을 넘은 과거 원소를 기록할 때마다 제거한다(tasks.md 항목 1 "sigma-lookback-hours를
	// 넘은 원소를 기록할 때마다 제거한다"). cutoff = recordedAt - retention 이전 구간을 제거 대상으로 넘겨야 한다.
	@Test
	void recordSnapshotPrunesElementsOlderThanRetentionOnEveryRecord() {
		PriceStore priceStore = priceStore();
		LocalDateTime recordedAt = NOW;
		Duration retention = Duration.ofHours(24);
		long expectedCutoffMillis = recordedAt.minus(retention).atZone(ZoneOffset.UTC).toInstant().toEpochMilli();

		priceStore.recordSnapshot("BTC", recordedAt, new BigDecimal("100"), retention);

		verify(zSetOperations).removeRangeByScore(
			"price:crypto:BTC:snapshots", Double.NEGATIVE_INFINITY, expectedCutoffMillis);
	}

	@Test
	void getSnapshotsReturnsEmptyListWhenNoMemberExistsInRange() {
		PriceStore priceStore = priceStore();
		when(zSetOperations.rangeByScore(eq("price:crypto:BTC:snapshots"), anyDouble(), anyDouble()))
			.thenReturn(Set.of());

		List<PriceSnapshotDto> snapshots = priceStore.getSnapshots("BTC", NOW.minusHours(1), NOW);

		assertThat(snapshots).isEmpty();
	}

	@Test
	void getSnapshotsParsesMembersAndSortsByRecordedAtAscending() {
		PriceStore priceStore = priceStore();
		LocalDateTime earlier = NOW.minusMinutes(10);
		LocalDateTime later = NOW.minusMinutes(5);
		long earlierMillis = earlier.atZone(ZoneOffset.UTC).toInstant().toEpochMilli();
		long laterMillis = later.atZone(ZoneOffset.UTC).toInstant().toEpochMilli();
		// 저장 순서와 반대로 반환돼도(later가 먼저) 결과는 시각 오름차순이어야 한다.
		when(zSetOperations.rangeByScore(eq("price:crypto:BTC:snapshots"), anyDouble(), anyDouble()))
			.thenReturn(Set.of(laterMillis + ":200", earlierMillis + ":100"));

		List<PriceSnapshotDto> snapshots = priceStore.getSnapshots("BTC", earlier, later);

		assertThat(snapshots).hasSize(2);
		assertThat(snapshots.get(0).recordedAt()).isEqualTo(earlier);
		assertThat(snapshots.get(0).price()).isEqualByComparingTo("100");
		assertThat(snapshots.get(1).recordedAt()).isEqualTo(later);
		assertThat(snapshots.get(1).price()).isEqualByComparingTo("200");
	}
}
