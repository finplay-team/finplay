// 랭킹 재구성 배치의 시장 단위 Redis 락 — 키 접두사와 TTL만 정하고 획득·해제 메커니즘은 RedisLock에 맡긴다 (ADR-0014 선례, 이슈 #539)
package com.finplay.api.domain.ranking.service;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.ranking.config.RankingRebuildProperties;
import com.finplay.api.global.lock.RedisLock;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code RankingRebuildService.rebuild(Market)}가 시장별 임시 키(ranking:{market}:rebuild)를 DEL·ZADD·RENAME하는
 * 동안 같은 시장을 잠근다. ADR-0021 §결정 7이 확정한 블루-그린 배포는 전환 이후에도 이전 색 인스턴스를 다음
 * 배포까지 내리지 않으므로, 두 인스턴스의 스케줄러가 같은 시각에 같은 시장을 재구성하면 한쪽의 DEL이 다른
 * 쪽이 ZADD로 채우던 내용을 지울 수 있다 — 그 상태로 RENAME이 라이브 키를 교체하면 매도 이력이 있는 계좌
 * 일부가 랭킹에서 조용히 빠진다(이슈 #539).
 *
 * <p>락 범위는 시장 단위다 — {@code rebuildAll()} 전체가 아니라 {@code rebuild(Market)} 호출 하나를 감싼다.
 * 실제 경합이 일어나는 단위(시장별 임시 키)와 정확히 일치하고, 기존의 "시장마다 try/catch로 감싸 한 시장의
 * 실패가 다른 시장을 막지 않는다"는 격리 설계와도 같은 결을 유지한다.
 *
 * <p><b>SET NX PX 획득과 Lua check-then-delete 해제는 {@link RedisLock}에 위임한다.</b> 이 클래스에 남는 것은 이
 * 배치 전용 결정 둘뿐이다 — 키 접두사 {@code ranking:rebuild:lock:}과 TTL
 * {@code ranking.rebuild.lock-ttl-seconds}(기본값 600초, {@code market.stock.collect-lock-ttl-seconds}와 같은
 * 근거 — 정상 실행 소요시간의 수 배 여유를 두면서도 프로세스가 죽어 finally를 못 도는 최후의 경우에도 다음
 * 재구성 기회 전까지만 잠기게 한다). 이 TTL은 랭킹 데이터의 실시간 신선도와 무관하다 — 실시간 갱신은
 * {@code RankingEventListener}가 매도 체결 커밋 직후 개별 계좌 점수를 바로 갱신하는 별도 경로이고, 이 배치는
 * Redis 유실 복구·정기 정합성 교정만 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankingRebuildLock {

	private static final String KEY_PREFIX = "ranking:rebuild:lock:";

	private final RedisLock redisLock;

	private final RankingRebuildProperties rebuildProperties;

	/**
	 * 주어진 시장의 재구성 락을 얻는다. 성공하면 이번 시도를 식별하는 토큰을 반환한다 — 해제할 때 그대로
	 * 넘겨야 한다. TTL은 {@code ranking.rebuild.lock-ttl-seconds}다.
	 *
	 * <p>다른 인스턴스(블루-그린의 나머지 한쪽)가 이미 이 시장을 재구성 중이거나, Redis 자체가 예외를
	 * 던지면(장애) 모두 획득 실패다 — 호출부는 이번 시장의 이번 실행만 조용히 건너뛴다.
	 */
	public Optional<String> tryLock(Market market) {
		return redisLock.tryLock(lockKey(market), Duration.ofSeconds(rebuildProperties.lockTtlSeconds()));
	}

	/**
	 * {@code tryLock}이 반환한 토큰으로만 해제한다. 지울 것이 없었으면(이미 TTL 만료 후 다른 인스턴스가 새로
	 * 잡은 락이면) {@code WARN}으로 남긴다 — TTL이 실제로 부족했다는 신호다. Redis 장애는 {@link RedisLock}이
	 * 이미 {@code WARN}으로 남겼으므로 여기서 다시 남기지 않는다.
	 */
	public void unlock(Market market, String token) {
		if (redisLock.unlock(lockKey(market), token) == RedisLock.UnlockResult.NOT_HELD) {
			log.warn(
				"랭킹 재구성 락 해제가 아무 것도 지우지 못했다(토큰 불일치 - TTL이 이미 만료돼 다른 "
					+ "인스턴스가 락을 새로 잡았을 수 있다) - market={}",
				market);
		}
	}

	private String lockKey(Market market) {
		return KEY_PREFIX + market;
	}
}
