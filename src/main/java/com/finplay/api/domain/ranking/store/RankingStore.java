package com.finplay.api.domain.ranking.store;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RankingStore {

	private static final String KEY_PREFIX = "ranking:";
	private static final int MAX_ATTEMPTS = 3;
	private static final long[] BACKOFF_MILLIS = {50, 150};
	private static final int FIND_ALL_AT_SCORE_MAX_MEMBERS = 500;
	private static final String REBUILD_KEY_SUFFIX = ":rebuild";
	public static final int REBUILD_CHUNK_SIZE = 500;

	private final StringRedisTemplate redisTemplate;

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

	public List<RankingEntryDto> topN(Market market, int limit) {
		Set<ZSetOperations.TypedTuple<String>> window;
		try {
			window = redisTemplate.opsForZSet().reverseRangeWithScores(key(market), 0, limit - 1);
		} catch (RedisConnectionFailureException | QueryTimeoutException e) {
			throw unavailable(market, e);
		}
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

	public List<RankingEntryDto> findAllAtScore(Market market, long score) {
		Set<String> members;
		try {
			members = redisTemplate.opsForZSet()
				.rangeByScore(key(market), (double)score, (double)score, 0, FIND_ALL_AT_SCORE_MAX_MEMBERS);
		} catch (RedisConnectionFailureException | QueryTimeoutException e) {
			throw unavailable(market, e);
		}
		if (members == null || members.isEmpty()) {
			return List.of();
		}
		if (members.size() >= FIND_ALL_AT_SCORE_MAX_MEMBERS) {
			log.warn("경계 동점 그룹이 상한을 초과해 절단함. market={}, score={}, cap={}",
				market, score, FIND_ALL_AT_SCORE_MAX_MEMBERS);
		}
		List<RankingEntryDto> entries = new ArrayList<>();
		for (String member : members) {
			entries.add(new RankingEntryDto(Long.valueOf(member), score));
		}
		return entries;
	}

	public long countStrictlyGreater(Market market, long score) {
		long lowerBound = Math.min(score, Long.MAX_VALUE - 1) + 1;
		Long count;
		try {
			count = redisTemplate.opsForZSet().count(key(market), lowerBound, Double.POSITIVE_INFINITY);
		} catch (RedisConnectionFailureException | QueryTimeoutException e) {
			throw unavailable(market, e);
		}
		return count == null ? 0 : count;
	}

	private BusinessException unavailable(Market market, Exception cause) {
		log.warn("랭킹 조회 실패(Redis 연결 장애). market={}, cause={}", market, cause.toString());
		return new BusinessException(ErrorCode.RANKING_STORE_UNAVAILABLE, cause);
	}

	private void sleepBackoff(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public Long score(Market market, Long accountId) {
		Double raw;
		try {
			raw = redisTemplate.opsForZSet().score(key(market), String.valueOf(accountId));
		} catch (RedisConnectionFailureException | QueryTimeoutException e) {
			throw unavailable(market, e);
		}
		return raw == null ? null : Math.round(raw);
	}

	public boolean replaceAll(Market market, List<RankingEntryDto> entries) {
		String key = key(market);
		String rebuildKey = rebuildKey(market);
		try {
			redisTemplate.delete(rebuildKey);
			if (entries.isEmpty()) {
				redisTemplate.delete(key);
				return true;
			}
			for (int start = 0; start < entries.size(); start += REBUILD_CHUNK_SIZE) {
				int end = Math.min(start + REBUILD_CHUNK_SIZE, entries.size());
				Set<ZSetOperations.TypedTuple<String>> chunk = new LinkedHashSet<>();
				for (RankingEntryDto entry : entries.subList(start, end)) {
					chunk.add(ZSetOperations.TypedTuple.of(String.valueOf(entry.accountId()), (double)entry.score()));
				}
				redisTemplate.opsForZSet().add(rebuildKey, chunk);
			}
			redisTemplate.rename(rebuildKey, key);
			return true;
		} catch (Exception e) {
			log.error("랭킹 ZSET 재구성 실패. market={}, 대상 계좌 수={}", market, entries.size(), e);
			cleanUpRebuildKey(rebuildKey, market);
			return false;
		}
	}

	private void cleanUpRebuildKey(String rebuildKey, Market market) {
		try {
			redisTemplate.delete(rebuildKey);
		} catch (Exception cleanupFailure) {
			log.warn("랭킹 재구성 임시 키 정리 실패. market={}", market, cleanupFailure);
		}
	}

	private String rebuildKey(Market market) {
		return key(market) + REBUILD_KEY_SUFFIX;
	}

	private String key(Market market) {
		return KEY_PREFIX + market.name();
	}
}
