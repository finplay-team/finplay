// 코인 최신 시세·수신시각·빗썸 연결상태를 Redis에 저장·조회하는 단일 창구 (key 문자열은 이 클래스에서만 조립)
package com.finplay.api.market.store;

import com.finplay.api.market.event.CryptoPriceUpdatedEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PriceStore {

	private static final String PRICE_KEY_PREFIX = "price:crypto:";
	private static final String SNAPSHOT_KEY_SUFFIX = ":snapshots";
	private static final String STATUS_KEY = "feed:crypto:status";
	private static final String FIELD_PRICE = "price";
	private static final String FIELD_RECEIVED_AT = "receivedAt";
	private static final String FIELD_OBSERVED_AT = "observedAt";
	private static final Duration STALE_THRESHOLD = Duration.ofSeconds(10);
	private static final String SNAPSHOT_MEMBER_DELIMITER = ":";

	private final StringRedisTemplate redisTemplate;
	private final Clock clock;
	private final ApplicationEventPublisher eventPublisher;

	// 동일 심볼의 과거 틱(수신시각이 현재 저장된 값보다 이전 또는 같음)은 최신 틱을 덮어쓰지 못한다 (spec.md MKT-003).
	// 이 가드는 receivedAt(체결 시각)끼리만 비교한다 — REST 폴러(recordObservation)가 항상 "지금" 시각을 관측 시각으로
	// 남기더라도 이 가드에는 영향을 주지 않는다(PRICE-REST-003, docs/specs/034-crypto-price-rest-backup/plan.md).
	// 이 분기를 통과해 실제로 최신값을 갱신했을 때만 CryptoPriceUpdatedEvent를 publish한다 (015-limit-order LMT-002 트리거,
	// spec.md 확정된 설계 결정 3번). 일반 ApplicationEvent다 — 가격 수신이 DB 트랜잭션이 아니므로 AFTER_COMMIT 대상이 없다.
	// 가드를 통과하면 observedAt(관측 시각)도 "지금"으로 함께 갱신한다 — 체결이 신선함을 웹소켓 수신 자체로도
	// 확인했다는 뜻이다(PRICE-REST-001).
	public void saveTick(String symbol, BigDecimal price, LocalDateTime receivedAt) {
		HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
		String key = priceKey(symbol);
		Optional<LocalDateTime> existingReceivedAt = readReceivedAt(hashOps, key);
		if (existingReceivedAt.isPresent() && !receivedAt.isAfter(existingReceivedAt.get())) {
			return;
		}
		LocalDateTime observedAt = LocalDateTime.now(clock);
		Map<String, String> fields = new HashMap<>();
		fields.put(FIELD_PRICE, price.toPlainString());
		fields.put(FIELD_RECEIVED_AT, receivedAt.toString());
		fields.put(FIELD_OBSERVED_AT, observedAt.toString());
		hashOps.putAll(key, fields);
		eventPublisher.publishEvent(new CryptoPriceUpdatedEvent(symbol, price, receivedAt, observedAt));
	}

	// REST 폴러(BithumbRestTickerPoller) 전용 — "이 가격이 지금도 최신"임을 재확인했다는 뜻으로 observedAt은
	// 항상 갱신한다. price는 저장된 값과 실제로 다를 때만 갱신하고, receivedAt(체결 시각)은 원칙적으로 절대
	// 건드리지 않는다 — REST는 체결 시각을 모르고 "폴링한 시각"만 알기 때문에 여기 채워 넣으면 MKT-003 가드가
	// 오염된다(PRICE-REST-001·002, docs/specs/034-crypto-price-rest-backup/plan.md).
	// 예외 — 이 심볼의 웹소켓 체결을 한 번도 받은 적 없을 때(서버 재시작 직후 REST가 그 심볼의 첫 웹소켓
	// 체결보다 먼저 도착하는 경우 등)만 receivedAt도 observedAt과 같은 값으로 부트스트랩한다. 그러지 않으면
	// getLatestPrice가 receivedAt 없음을 이유로 계속 Optional.empty()를 돌려줘, REST가 신선한 가격을 확보했는데도
	// "받은 적 없음" UNAVAILABLE이 배포 직후 저유동성 종목에서 재현된다(리뷰 [권장], PR 리뷰 지적). 이 부트스트랩은
	// 딱 한 번 "받은 적 없음" 상태를 벗어나게 할 뿐이다 — 그 뒤 실제 웹소켓 체결이 도착하면 부트스트랩 시각보다
	// 나중이므로 MKT-003 가드가 정상적으로 덮어쓴다.
	// CryptoPriceUpdatedEvent는 가격이 실제로 바뀌었을 때만 publish한다 — 관측 시각만 갱신한 호출은 소비자
	// (LimitOrderTriggerListener) 입장에서 같은 값을 3초마다 재처리하는 순수한
	// 낭비이기 때문이다(plan.md "컴포넌트 설계 — PriceStore" §이벤트 발행). 신규 심볼의 첫 관측은 price가 null에서
	// 값이 생기는 것이므로 항상 priceChanged=true라 이 조건에 자연히 포함된다. 이벤트의 receivedAt은 이 메서드가
	// 건드리지 않는 기존 체결 시각을 그대로 실어 보낸다 — receivedAt이 아예 없던 심볼은 방금 부트스트랩한 값(=observedAt)을 쓴다.
	public void recordObservation(String symbol, BigDecimal price, LocalDateTime observedAt) {
		HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
		String key = priceKey(symbol);
		String existingPriceValue = hashOps.get(key, FIELD_PRICE);
		boolean priceChanged = existingPriceValue == null || new BigDecimal(existingPriceValue).compareTo(price) != 0;
		Optional<LocalDateTime> existingReceivedAt = readReceivedAt(hashOps, key);
		Map<String, String> fields = new HashMap<>();
		fields.put(FIELD_OBSERVED_AT, observedAt.toString());
		if (priceChanged) {
			fields.put(FIELD_PRICE, price.toPlainString());
		}
		if (existingReceivedAt.isEmpty()) {
			fields.put(FIELD_RECEIVED_AT, observedAt.toString());
		}
		hashOps.putAll(key, fields);
		if (priceChanged) {
			LocalDateTime receivedAt = existingReceivedAt.orElse(observedAt);
			eventPublisher.publishEvent(new CryptoPriceUpdatedEvent(symbol, price, receivedAt, observedAt));
		}
	}

	public Optional<CryptoPriceDto> getLatestPrice(String symbol) {
		HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
		String key = priceKey(symbol);
		String priceValue = hashOps.get(key, FIELD_PRICE);
		Optional<LocalDateTime> receivedAt = readReceivedAt(hashOps, key);
		if (priceValue == null || receivedAt.isEmpty()) {
			return Optional.empty();
		}
		// observedAt 필드가 없는 기존 해시(배포 전 데이터)는 receivedAt을 관측 시각으로 간주한다 — 배포 전과
		// 동일한 신선도 판정을 유지한다(PRICE-REST-001).
		LocalDateTime observedAt = readObservedAt(hashOps, key).orElse(receivedAt.get());
		return Optional.of(new CryptoPriceDto(symbol, new BigDecimal(priceValue), receivedAt.get(), observedAt));
	}

	public void saveConnectionStatus(FeedConnectionStatus status) {
		redisTemplate.opsForValue().set(STATUS_KEY, status.name());
	}

	// 연결상태를 저장한 적이 없으면 DISCONNECTED로 취급한다 (장애 중 임의 가격 전환 금지 — fail-closed).
	public FeedConnectionStatus getConnectionStatus() {
		String value = redisTemplate.opsForValue().get(STATUS_KEY);
		return value == null ? FeedConnectionStatus.DISCONNECTED : FeedConnectionStatus.valueOf(value);
	}

	// 주어진 시각이 10초를 초과하면 stale(유효하지 않음)로 판정한다. 판정 기준 시각은 관측 시각(observedAt)이다
	// (PRICE-REST-001, docs/specs/034-crypto-price-rest-backup) — 호출자가 CryptoPriceDto.observedAt()을 넘겨야
	// REST 폴링이 신선도를 유지하는 효과가 실제로 반영된다. 표시·체결 판정(PriceQueryService)은 036 이후 이
	// 메서드를 더 이상 호출하지 않는다 — 지금은 isPriceAvailable() 경유로 CryptoPriceSnapshotService(변동 카드
	// 재료 신뢰도 게이트, PRICE-NOSTALE-004)만 이 stale 개념을 쓴다.
	public boolean isStale(LocalDateTime observedAt) {
		Duration elapsed = Duration.between(observedAt, LocalDateTime.now(clock));
		return elapsed.compareTo(STALE_THRESHOLD) > 0;
	}

	// 연결이 끊겼거나 최신 틱이 stale이면 유효하지 않은 가격으로 판정한다. PriceQueryService의 코인 표시·체결
	// 경로는 이 메서드를 호출하지 않는다 — 036 이후 관측 시각과 무관하게 항상 AVAILABLE로 취급한다(PRICE-NOSTALE-001,
	// docs/specs/036-remove-crypto-stale-status). CryptoPriceSnapshotService(변동 카드 재료 신뢰도 게이트,
	// PRICE-NOSTALE-004)는 여전히 이 메서드로 stale 심볼을 건너뛴다.
	public boolean isPriceAvailable(String symbol) {
		if (getConnectionStatus() != FeedConnectionStatus.CONNECTED) {
			return false;
		}
		return getLatestPrice(symbol)
			.map(price -> !isStale(price.receivedAt()))
			.orElse(false);
	}

	// 계좌(=market) 단위로 여러 심볼을 평가할 때 심볼과 무관한 전역 연결상태(getConnectionStatus, Redis 전역 키
	// feed:crypto:status)를 요청당 1회만 조회하고, 심볼별로 실제로 달라지는 개별 가격 조회만 반복한다 (PR #97 리뷰 권장사항).
	// 연결이 끊겨 있으면 개별 가격 조회 없이 즉시 빈 Map을 반환한다 (fail-closed, isPriceAvailable과 동일 정책).
	public Map<String, CryptoPriceDto> getLatestPrices(List<String> symbols) {
		if (getConnectionStatus() != FeedConnectionStatus.CONNECTED) {
			return Map.of();
		}
		Map<String, CryptoPriceDto> prices = new HashMap<>();
		for (String symbol : symbols) {
			getLatestPrice(symbol)
				.filter(price -> !isStale(price.receivedAt()))
				.ifPresent(price -> prices.put(symbol, price));
		}
		return prices;
	}

	// 코인 가격 스냅샷 1건을 Sorted Set에 적재하고, retention을 넘은 과거 원소를 함께 제거한다
	// (spec 012 §코인 가격 스냅샷 — CryptoPriceSnapshotService가 매 분 호출한다). 키 조립은 이 클래스 안에서만
	// 한다(docs/conventions/code.md). score는 recordedAt의 epoch millis, member는 "{epochMillis}:{price}"다 —
	// 수익률이 아니라 가격+시각을 저장해야 나중에 임의 구간의 "N분 전 가격"을 꺼낼 수 있다.
	public void recordSnapshot(String symbol, LocalDateTime recordedAt, BigDecimal price, Duration retention) {
		String key = snapshotKey(symbol);
		long epochMillis = toEpochMillis(recordedAt);
		String member = epochMillis + SNAPSHOT_MEMBER_DELIMITER + price.toPlainString();
		redisTemplate.opsForZSet().add(key, member, epochMillis);
		long cutoffMillis = toEpochMillis(recordedAt.minus(retention));
		redisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoffMillis);
	}

	// [from, to] 구간의 스냅샷을 시각 오름차순으로 반환한다. feedback은 이 메서드를 직접 호출하지 않고
	// CryptoPriceSnapshotService.getSnapshots만 거친다(§C-6 — market은 전부 서비스를 경유한다).
	public List<PriceSnapshotDto> getSnapshots(String symbol, LocalDateTime from, LocalDateTime to) {
		String key = snapshotKey(symbol);
		Set<String> members = redisTemplate.opsForZSet().rangeByScore(key, toEpochMillis(from), toEpochMillis(to));
		if (members == null || members.isEmpty()) {
			return List.of();
		}
		List<PriceSnapshotDto> snapshots = new ArrayList<>();
		for (String member : members) {
			int delimiterIndex = member.indexOf(SNAPSHOT_MEMBER_DELIMITER);
			long epochMillis = Long.parseLong(member.substring(0, delimiterIndex));
			BigDecimal price = new BigDecimal(member.substring(delimiterIndex + 1));
			snapshots.add(new PriceSnapshotDto(toLocalDateTime(epochMillis), price));
		}
		snapshots.sort(Comparator.comparing(PriceSnapshotDto::recordedAt));
		return snapshots;
	}

	private Optional<LocalDateTime> readReceivedAt(HashOperations<String, String, String> hashOps, String key) {
		String value = hashOps.get(key, FIELD_RECEIVED_AT);
		return value == null ? Optional.empty() : Optional.of(LocalDateTime.parse(value));
	}

	private Optional<LocalDateTime> readObservedAt(HashOperations<String, String, String> hashOps, String key) {
		String value = hashOps.get(key, FIELD_OBSERVED_AT);
		return value == null ? Optional.empty() : Optional.of(LocalDateTime.parse(value));
	}

	private String priceKey(String symbol) {
		return PRICE_KEY_PREFIX + symbol;
	}

	private String snapshotKey(String symbol) {
		return PRICE_KEY_PREFIX + symbol + SNAPSHOT_KEY_SUFFIX;
	}

	// 이 저장소의 LocalDateTime은 전부 clock(Asia/Seoul) 기준 벽시계 값이다 — Redis score(epoch millis)와
	// 상호 변환할 때 이 zone을 일관되게 쓴다.
	private long toEpochMillis(LocalDateTime dateTime) {
		return dateTime.atZone(clock.getZone()).toInstant().toEpochMilli();
	}

	private LocalDateTime toLocalDateTime(long epochMillis) {
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), clock.getZone());
	}
}
