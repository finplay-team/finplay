// 시장별 실현손익 랭킹 ZSET을 Redis에 저장·조회하는 전용 창구 (key 문자열은 이 클래스에서만 조립)
package com.finplay.api.ranking.store;

import com.finplay.api.account.domain.Market;
import com.finplay.api.ranking.dto.RankingEntryDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RankingStore {

	private static final String KEY_PREFIX = "ranking:";
	private static final int MAX_ATTEMPTS = 3;
	// MAX_ATTEMPTS번째 시도는 실패해도 sleep 없이 즉시 포기하므로, 배열 길이는 MAX_ATTEMPTS-1이어야 한다.
	private static final long[] BACKOFF_MILLIS = {50, 150};

	private final StringRedisTemplate redisTemplate;

	// Redis 갱신 실패 시(1차 정책, spec.md): 재시도(backoff) 후에도 실패하면 로그만 남기고 예외를 삼킨다.
	// 매도 체결(주문·체결·계좌 갱신)은 이 메서드의 실패 영향을 받지 않아야 한다 — 예외를 던지지 않는다.
	public void addScoreWithRetry(Market market, Long accountId, long score) {
		String key = key(market);
		String member = String.valueOf(accountId);
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				redisTemplate.opsForZSet().add(key, member, (double)score);
				return;
			} catch (Exception e) {
				if (attempt == MAX_ATTEMPTS) {
					log.error("랭킹 ZSET 갱신 실패(재시도 소진). accountId={}, market={}", accountId, market, e);
					return;
				}
				sleepBackoff(BACKOFF_MILLIS[attempt - 1]);
			}
		}
	}

	// score desc 상위 limit개를 (accountId, score)로 반환한다. 동점자 내부 정렬·공동 순위 보정은 RankingService 책임이다.
	public List<RankingEntryDto> topN(Market market, int limit) {
		Set<ZSetOperations.TypedTuple<String>> window = redisTemplate.opsForZSet().reverseRangeWithScores(key(market),
			0, limit - 1);
		if (window == null) {
			return List.of();
		}
		List<RankingEntryDto> entries = new ArrayList<>();
		for (ZSetOperations.TypedTuple<String> tuple : window) {
			String member = tuple.getValue();
			Double score = tuple.getScore();
			if (member == null || score == null) {
				continue;
			}
			entries.add(new RankingEntryDto(Long.valueOf(member), Math.round(score)));
		}
		return entries;
	}

	// 정확히 score인 멤버 전체를 가져온다 — limit 경계에 동점 그룹이 걸쳐 있을 때(RankingService의 boundary tie
	// 병합) 그 score의 전체 멤버를 다시 가져오기 위한 조회다. ZRANGEBYSCORE score score와 동치(정확한 구간 조회,
	// 스코어 근사 없음). 이 메서드가 반환하는 개수는 동점자 수에 비례하므로(수백 명까지 있을 수 있음) 경계에
	// 동점이 확인된 경우에만 호출한다.
	public List<RankingEntryDto> findAllAtScore(Market market, long score) {
		Set<String> members = redisTemplate.opsForZSet().rangeByScore(key(market), (double)score, (double)score);
		if (members == null || members.isEmpty()) {
			return List.of();
		}
		List<RankingEntryDto> entries = new ArrayList<>();
		for (String member : members) {
			entries.add(new RankingEntryDto(Long.valueOf(member), score));
		}
		return entries;
	}

	// score보다 엄격히 큰 멤버 수 — 공동 순위 계산(rank = countStrictlyGreater + 1)에 쓰인다.
	// plan.md는 Range.rightUnbounded(Range.Bound.exclusive(score))를 스케치했지만, 현재 spring-data-redis(3.5.6)
	// ZSetOperations에는 Range<Double>를 받는 count 오버로드가 없다(count(K, double, double)만 존재).
	// score(accounts.realized_pnl)는 항상 정수(long)이므로 하한을 score+1로 잡아도 "엄격히 큼"과 동치다.
	public long countStrictlyGreater(Market market, long score) {
		// score == Long.MAX_VALUE면 score+1이 오버플로해 Long.MIN_VALUE가 되므로 하한을 클램핑한다.
		// 클램핑된 경우 lowerBound == score라 자기 자신도 포함돼 "엄격히 큼"이 정확히는 아니지만,
		// KRW 실현손익 규모에서 이 값에 도달할 수 없어 더 정교하게 고치지 않는다(PR #196 리뷰 참고).
		long lowerBound = Math.min(score, Long.MAX_VALUE - 1) + 1;
		Long count = redisTemplate.opsForZSet().count(key(market), lowerBound, Double.POSITIVE_INFINITY);
		return count == null ? 0 : count;
	}

	private void sleepBackoff(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// 계좌 하나의 score를 조회한다(RANK-002 내 랭킹 조회). ZSET에 member가 없으면(매도 이력 없음) null을 반환한다
	// — RankingService.getMyRanking이 이 null 여부로 매도 이력 유무를 판정한다(plan.md "RANK-002 설계" 참고).
	public Long score(Market market, Long accountId) {
		Double raw = redisTemplate.opsForZSet().score(key(market), String.valueOf(accountId));
		return raw == null ? null : Math.round(raw);
	}

	private String key(Market market) {
		return KEY_PREFIX + market.name();
	}
}
