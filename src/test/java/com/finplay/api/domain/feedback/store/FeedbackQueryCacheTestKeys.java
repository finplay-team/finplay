// 테스트 전용 — 공유 Testcontainers Redis에 남은 조회 캐시 키를 지운다. 캐시가 켜진 통합 테스트의 메서드 간 격리에 쓴다.
package com.finplay.api.domain.feedback.store;

import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * <b>{@code @Transactional} 통합 테스트에서 DB는 롤백되지만 Redis는 롤백되지 않는다.</b> Testcontainers Redis는
 * 실행 전체가 공유하는 static 싱글턴이라({@code TestcontainersConfiguration}) 한 테스트가 채운 캐시가 다음
 * 테스트에 그대로 보인다 — 롤백된 DB 상태와 살아남은 캐시가 어긋나 <b>단정이 조용히 다른 값을 본다.</b>
 * 실제로 이슈 #245에서 {@code MarketBriefingQueryGateIntegrationTest} 8건이 이 이유로 깨졌다
 * ({@code ai/agent-mistakes.md}).
 *
 * <p><b>{@code @BeforeEach}에서 부른다 — {@code @AfterEach}가 아니다.</b> 앞 테스트가 정리에 실패하거나
 * 예외로 중단돼도 이번 테스트는 항상 빈 캐시에서 시작한다. 정리를 뒤에 두면 그 실패가 다음 테스트의 실패로
 * 옮겨붙어 원인이 한 칸씩 밀린다.
 *
 * <p><b>키를 하나씩 세어 지우지 않고 접두사로 쓸어 담는다.</b> 캐시 항목이 늘어도 청소가 따라오지 못해 조용히
 * 새는 일이 없다. 테스트 전용 Redis라 {@code KEYS} 사용이 문제되지 않는다.
 *
 * <p>상속을 강요하지 않으려고 static 유틸로 둔다 — 기존 게이트 테스트들은 각자 다른 베이스 없이 이 한 줄만
 * 추가하면 된다.
 */
public final class FeedbackQueryCacheTestKeys {

	/**
	 * {@code FeedbackQueryCache}가 쓰는 모든 키(값·락 공통 접두사).
	 *
	 * <p><b>스키마 세그먼트({@code v1})를 넣지 않는다</b> — 여기 목적은 정리이므로 옛 버전이 남긴 키까지 쓸어야
	 * 한다. 버전을 박으면 스키마를 올린 뒤 이전 버전 키가 공유 Redis에 남아, 그것을 읽는 테스트가 생겼을 때
	 * 조용히 살아남는다. 반대로 <b>키를 단정하는</b> 쪽은 리터럴에 {@code v1}을 그대로 적어야 접두사가 바뀌면
	 * 테스트가 깨진다.
	 */
	public static final String PATTERN = "feedback:query-cache:*";

	private FeedbackQueryCacheTestKeys() {}

	public static void clear(StringRedisTemplate redisTemplate) {
		Set<String> keys = redisTemplate.keys(PATTERN);
		if (keys != null && !keys.isEmpty()) {
			redisTemplate.delete(keys);
		}
	}
}
