// 코인 체결을 모아 1분봉(OHLCV)을 Redis에 저장·조회하는 단일 창구 (key 문자열은 이 클래스에서만 조립)
package com.finplay.api.domain.market.store;

import com.finplay.api.domain.market.service.CryptoCandleDto;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoCandleStore {

	private static final String CANDLE_KEY_PREFIX = "candle:crypto:";
	private static final String CANDLE_KEY_SUFFIX = ":1m:";
	private static final String SINCE_KEY_SUFFIX = ":1m:since";
	private static final String FIELD_OPEN = "open";
	private static final String FIELD_HIGH = "high";
	private static final String FIELD_LOW = "low";
	private static final String FIELD_CLOSE = "close";
	private static final String FIELD_VOLUME_SCALED = "volumeScaled";

	// 응답 상한 200봉(=200분)에서 역산한 값이다(spec "왜 TTL이 4시간인가") — 성능 추측이 아니라 계약에서 유도했다.
	private static final long CANDLE_TTL_SECONDS = Duration.ofHours(4).toSeconds();
	// 코인 수량의 소수 자릿수는 OrderExecutionService·LimitOrderCreationService·LimitOrderModifyService에서
	// 이미 8자리로 고정돼 있다(scale() > 8이면 주문 거부). 그 관례를 그대로 따라 거래량을 정수로 스케일링해
	// HINCRBY(정수 연산)로 누적한다 — 반복 덧셈에서 부동소수 오차가 쌓이지 않게 하기 위함이다.
	private static final int QUANTITY_SCALE = 8;

	// KEYS[1]=분봉 키, ARGV[1]=체결가(문자열), ARGV[2]=스케일링된 체결수량(정수 문자열), ARGV[3]=TTL(초).
	// 읽고-비교하고-쓰는 여러 단계를 Lua 스크립트 하나로 묶어 원자적으로 실행한다 — 체결이 동시에 여러 건
	// 들어와도 high/low/volume이 유실되지 않는다(plan.md "원자적 갱신").
	private static final RedisScript<Long> RECORD_TRADE_SCRIPT = new DefaultRedisScript<>(
		"""
			local key = KEYS[1]
			local price = ARGV[1]
			local qtyScaled = ARGV[2]
			local ttl = ARGV[3]
			if redis.call('EXISTS', key) == 0 then
			    redis.call('HSET', key, 'open', price, 'high', price, 'low', price, 'close', price, 'volumeScaled', qtyScaled)
			else
			    local high = redis.call('HGET', key, 'high')
			    local low = redis.call('HGET', key, 'low')
			    if tonumber(price) > tonumber(high) then
			        redis.call('HSET', key, 'high', price)
			    end
			    if tonumber(price) < tonumber(low) then
			        redis.call('HSET', key, 'low', price)
			    end
			    redis.call('HSET', key, 'close', price)
			    redis.call('HINCRBY', key, 'volumeScaled', qtyScaled)
			end
			redis.call('EXPIRE', key, ttl)
			return 1
			""",
		Long.class);

	private final StringRedisTemplate redisTemplate;
	private final Clock clock;

	// 체결 하나를 그 체결이 속한 분의 봉에 반영한다. quantity의 소수 자릿수가 QUANTITY_SCALE(8)을 넘으면
	// 조용히 반올림해 거래량을 왜곡하지 않고, 그 체결을 집계에서 제외한 뒤 로그만 남긴다(plan.md 방어 규칙).
	// 이미 지난 분에 속한 체결이 뒤늦게 오면 그 분의 봉을 갱신하지 않고 버린다(plan.md "늦게 온 체결" —
	// 이미 응답으로 나간 값과 어긋나지 않게 하기 위함, PriceStore.saveTick의 과거 틱 무시와 같은 방향).
	public void recordTrade(String symbol, LocalDateTime tradedAt, BigDecimal price, BigDecimal quantity) {
		long tradeMinute = toEpochMinute(tradedAt);
		long currentMinute = toEpochMinute(LocalDateTime.now(clock));
		if (tradeMinute < currentMinute) {
			log.warn("이미 지난 분에 속한 체결을 무시합니다: symbol={}, tradedAt={}", symbol, tradedAt);
			return;
		}
		if (quantity.stripTrailingZeros().scale() > QUANTITY_SCALE) {
			log.warn("코인 체결 수량의 소수 자릿수가 {}를 초과해 집계에서 제외합니다: symbol={}, quantity={}", QUANTITY_SCALE, symbol,
				quantity);
			return;
		}
		String key = candleKey(symbol, tradeMinute);
		long quantityScaled = quantity.movePointRight(QUANTITY_SCALE).longValueExact();
		redisTemplate.execute(RECORD_TRADE_SCRIPT, List.of(key), price.toPlainString(),
			String.valueOf(quantityScaled), String.valueOf(CANDLE_TTL_SECONDS));
	}

	// [from, to] 구간(분 단위로 내림)에 존재하는 분봉만 반환한다. 체결이 없던 분은 목록에 없다(0으로 채운
	// 봉을 만들지 않는다, spec "체결이 하나도 없는 분은 봉을 만들지 않는다"). 필요한 분 키 전체를 파이프라인
	// 1회로 조회해 라운드트립을 하나로 줄인다.
	public List<CryptoCandleDto> getCandles(String symbol, LocalDateTime from, LocalDateTime to) {
		long fromMinute = toEpochMinute(from);
		long toMinute = toEpochMinute(to);
		if (fromMinute > toMinute) {
			return List.of();
		}
		List<String> keys = new ArrayList<>();
		for (long minute = fromMinute; minute <= toMinute; minute++) {
			keys.add(candleKey(symbol, minute));
		}

		List<Object> results = redisTemplate.executePipelined((RedisConnection connection) -> {
			for (String key : keys) {
				connection.hashCommands().hGetAll(key.getBytes(StandardCharsets.UTF_8));
			}
			return null;
		});

		List<CryptoCandleDto> candles = new ArrayList<>();
		for (int i = 0; i < results.size(); i++) {
			@SuppressWarnings("unchecked") Map<String, String> fields = (Map<String, String>)results.get(i);
			if (fields == null || fields.isEmpty()) {
				continue;
			}
			candles.add(toDto(fromEpochMinute(fromMinute + i), fields));
		}
		return candles;
	}

	// WebSocket 연결이 성립될 때마다 호출한다(BithumbWebSocketFeedClient.afterConnectionEstablished). 이
	// 시점 이후만 우리 데이터가 연속적으로 신뢰 가능하다는 워터마크다(plan.md "since 워터마크").
	public void touchSince(String symbol, LocalDateTime now) {
		redisTemplate.opsForValue()
			.set(sinceKey(symbol), String.valueOf(toEpochMinute(now)), Duration.ofSeconds(CANDLE_TTL_SECONDS));
	}

	public Optional<LocalDateTime> getSince(String symbol) {
		String value = redisTemplate.opsForValue().get(sinceKey(symbol));
		return value == null ? Optional.empty() : Optional.of(fromEpochMinute(Long.parseLong(value)));
	}

	private CryptoCandleDto toDto(LocalDateTime sourceTime, Map<String, String> fields) {
		BigDecimal volume = new BigDecimal(fields.get(FIELD_VOLUME_SCALED)).movePointLeft(QUANTITY_SCALE);
		return new CryptoCandleDto(sourceTime, new BigDecimal(fields.get(FIELD_OPEN)),
			new BigDecimal(fields.get(FIELD_HIGH)), new BigDecimal(fields.get(FIELD_LOW)),
			new BigDecimal(fields.get(FIELD_CLOSE)), volume);
	}

	private String candleKey(String symbol, long epochMinute) {
		return CANDLE_KEY_PREFIX + symbol + CANDLE_KEY_SUFFIX + epochMinute;
	}

	private String sinceKey(String symbol) {
		return CANDLE_KEY_PREFIX + symbol + SINCE_KEY_SUFFIX;
	}

	// 이 저장소의 LocalDateTime은 전부 clock(Asia/Seoul) 기준 벽시계 값이다 — PriceStore와 같은 원칙으로
	// epochMinute(Redis에 저장하는 정수)와 상호 변환할 때 이 zone을 일관되게 쓴다.
	private long toEpochMinute(LocalDateTime dateTime) {
		return dateTime.atZone(clock.getZone()).toEpochSecond() / 60;
	}

	private LocalDateTime fromEpochMinute(long epochMinute) {
		return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochMinute * 60), clock.getZone());
	}
}
