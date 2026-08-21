// 시장별 실현손익 랭킹 ZSET을 Redis에 저장·조회하는 전용 창구 (key 문자열은 이 클래스에서만 조립)
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
	// MAX_ATTEMPTS번째 시도는 실패해도 sleep 없이 즉시 포기하므로, 배열 길이는 MAX_ATTEMPTS-1이어야 한다.
	private static final long[] BACKOFF_MILLIS = {50, 150};
	// findAllAtScore의 fan-out 상한(이슈 #270) — 경계 score가 흔한 값(특히 실현손익 0)이면 동점자 전원이
	// Redis에서 애플리케이션 메모리로 올라와 DB IN 절 조회까지 하게 된다. RankingService.MAX_LIMIT(50)의
	// 10배로 잡아 정상 규모의 동점 그룹은 전혀 자르지 않으면서도 비정상 규모의 fan-out만 막는다.
	private static final int FIND_ALL_AT_SCORE_MAX_MEMBERS = 500;
	// 재구성(이슈 #279) 임시 키 suffix와 ZADD 청크 크기. key 문자열은 이 클래스에서만 조립한다(conventions.md).
	private static final String REBUILD_KEY_SUFFIX = ":rebuild";
	// 재구성 한 번에 묶어 보내는 계좌 수. ZADD 한 번의 크기이자 그 앞에 오는 계좌 배치 조회(IN 절)의 크기이기도
	// 하다 — RankingRebuildService가 이 상수를 그대로 참조해 DB 조회를 같은 크기로 나눈다(PR #284 리뷰).
	// 값을 복제하지 않는 이유: Redis 쪽만 나눠 봐야 앞단 IN 절 파라미터·패킷이 먼저 한계에 닿는다.
	public static final int REBUILD_CHUNK_SIZE = 500;

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
	// 쓰기 경로(addScoreWithRetry·replaceAll)와 달리 읽기 경로는 실패를 삼키지 않는다 — 읽기 실패는 호출자가
	// 신뢰할 수 없는 값을 그대로 응답에 실어 보낼 위험이 있어, RankingService가 UNAVAILABLE로 변환할 수 있게
	// 예외로 알려야 한다(이슈 #288). RedisConnectionFailureException·QueryTimeoutException만 잡아 감싼다 —
	// "연결 장애"로 응답하는 상태값의 의미와 catch 범위를 정확히 맞추기 위해서다(PR #296 리뷰 권장사항).
	// 이보다 넓은 DataAccessException(예: WRONGTYPE 등 데이터 오염이 RedisSystemException으로 번역된 경우)은
	// 여기서 잡지 않고 그대로 전파해 500으로 드러나게 한다 — 재시도로 저절로 낫지 않는 버그를 "일시 장애"로
	// 위장해 조용히 UNAVAILABLE로 감추지 않기 위해서다.
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

	// 정확히 score인 멤버 전체를 가져온다 — limit 경계에 동점 그룹이 걸쳐 있을 때(RankingService의 boundary tie
	// 병합) 그 score의 전체 멤버를 다시 가져오기 위한 조회다. ZRANGEBYSCORE score score와 동치(정확한 구간 조회,
	// 스코어 근사 없음). 이 메서드가 반환하는 개수는 동점자 수에 비례하므로(수백 명까지 있을 수 있음) 경계에
	// 동점이 확인된 경우에만 호출한다. FIND_ALL_AT_SCORE_MAX_MEMBERS를 Redis LIMIT 옵션으로 직접 넘겨, 상한을
	// 넘는 멤버를 애초에 애플리케이션 메모리로 가져오지 않는다(이슈 #270) — 절단된 상태에서는 동점자 내부
	// 정렬(userId 오름차순)이 정확하지 않을 수 있으나, 이 경로는 이미 비정상 규모의 동점 상황에서만 타므로
	// 과설계하지 않는다(PR #196 리뷰가 반복적으로 확인한 태도, plan.md 8-1·8-4절과 같은 기준).
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

	// score보다 엄격히 큰 멤버 수 — 공동 순위 계산(rank = countStrictlyGreater + 1)에 쓰인다.
	// plan.md는 Range.rightUnbounded(Range.Bound.exclusive(score))를 스케치했지만, 현재 spring-data-redis(3.5.6)
	// ZSetOperations에는 Range<Double>를 받는 count 오버로드가 없다(count(K, double, double)만 존재).
	// score(accounts.realized_pnl)는 항상 정수(long)이므로 하한을 score+1로 잡아도 "엄격히 큼"과 동치다.
	public long countStrictlyGreater(Market market, long score) {
		// score == Long.MAX_VALUE면 score+1이 오버플로해 Long.MIN_VALUE가 되므로 하한을 클램핑한다.
		// 클램핑된 경우 lowerBound == score라 자기 자신도 포함돼 "엄격히 큼"이 정확히는 아니지만,
		// KRW 실현손익 규모에서 이 값에 도달할 수 없어 더 정교하게 고치지 않는다(PR #196 리뷰 참고).
		long lowerBound = Math.min(score, Long.MAX_VALUE - 1) + 1;
		Long count;
		try {
			count = redisTemplate.opsForZSet().count(key(market), lowerBound, Double.POSITIVE_INFINITY);
		} catch (RedisConnectionFailureException | QueryTimeoutException e) {
			throw unavailable(market, e);
		}
		return count == null ? 0 : count;
	}

	// 읽기 경로 4곳(topN·findAllAtScore·countStrictlyGreater·score)이 공유하는 예외 변환 지점이다.
	// 로그는 여기서 한 번만 남긴다 — 호출부(RankingService)가 다시 잡아 UNAVAILABLE로 바꿀 때 또 남기지 않는다.
	// WARN + 스택트레이스 생략(PR #296 리뷰 권장사항) — 이제 이 경로는 500이 아니라 정상 폴백이라, 장애가
	// 몇 분만 이어져도 요청 수만큼 동일 스택트레이스가 쌓여 정작 봐야 할 다른 ERROR를 덮는다. 예외 타입·메시지는
	// 원인 파악에 충분하고, 반복 여부·지속 시간은 이 로그 라인 자체의 빈도로 알 수 있다.
	// 상세 원인(시장·예외)은 여기서 로그로만 남기고, 예외 메시지는 ErrorCode 기본 메시지로 고정한다 — 이 메시지가
	// GlobalExceptionHandler를 거쳐 그대로 응답 본문에 실리므로, RankingService의 catch를 빠뜨린 새 호출자가
	// 생겨도 내부 진단 문자열이 클라이언트로 새지 않게 하기 위해서다(PR #523 리뷰 [권장 5]).
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

	// 계좌 하나의 score를 조회한다(RANK-002 내 랭킹 조회). ZSET에 member가 없으면(매도 이력 없음) null을 반환한다
	// — RankingService.getMyRanking이 이 null 여부로 매도 이력 유무를 판정한다(plan.md "RANK-002 설계" 참고).
	public Long score(Market market, Long accountId) {
		Double raw;
		try {
			raw = redisTemplate.opsForZSet().score(key(market), String.valueOf(accountId));
		} catch (RedisConnectionFailureException | QueryTimeoutException e) {
			throw unavailable(market, e);
		}
		return raw == null ? null : Math.round(raw);
	}

	// 랭킹 ZSET을 통째로 교체한다(이슈 #279 재구성). 임시 키 ranking:{market}:rebuild에 전량 적재한 뒤
	// RENAME으로 원자 교체해, 재구성 중 조회가 빈 값·부분 값을 보지 않게 한다.
	// 순서는 DEL(임시 키 잔재 제거) → 청크 ZADD → RENAME이다. 1단계를 빼면 이전 실행이 중간에 죽어 남긴
	// 잔재와 이번 결과가 합쳐진 ZSET이 만들어진다.
	// 경계: entries가 0건이면 ZADD가 한 번도 실행되지 않아 임시 키가 존재하지 않고, 없는 키에 대한 RENAME은
	// Redis에서 ERR no such key다. 그래서 RENAME 대신 본 키를 DEL한다 — 매도 이력 계좌가 하나도 없으면
	// 랭킹이 비어 있는 것이 유실 전 상태와도 일치한다(본 키를 그대로 두면 사라진 계좌가 영원히 남는다).
	// 실패 처리: 전체를 try/catch로 감싸 예외를 밖으로 전파하지 않는다 — 호출자가 기동 훅과 스케줄러라
	// 예외가 새면 기동이 실패하거나 스케줄러 스레드가 죽는다. addScoreWithRetry가 매도 체결을 지키려고
	// 예외를 삼키는 것과 같은 방침이며, 재구성은 다음 기동·다음 배치에 다시 도는 멱등 작업이다.
	// 재시도는 하지 않는다(즉시 재시도의 값어치가 낮다, plan.md "실패 처리").
	//
	// 다만 예외를 삼키는 것과 성공/실패를 감추는 것은 다르다(PR #284 QA 지적) — 교체 성공 여부를 boolean으로
	// 돌려준다. 이 값이 없으면 호출자는 Redis가 죽은 tick에서도 "재구성 완료" INFO를 남겨, 로그만 보는 사람이
	// 실패를 성공으로 읽는다. 반환값을 무시해도 이 메서드는 여전히 예외를 던지지 않는다.
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

	// 실패 시 임시 키 정리를 한 번 시도한다. 정리 자체가 실패해도 무시한다 — 다음 실행의 DEL이 어차피 지운다.
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
