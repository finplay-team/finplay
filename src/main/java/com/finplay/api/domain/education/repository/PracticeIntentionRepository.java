// 투자 실습 사전 의도를 사용자별로 힙 메모리에 누적 저장하는 저장소(#193: JPA 리포지토리에서 전환)
package com.finplay.api.domain.education.repository;

import com.finplay.api.domain.education.model.PracticeIntention;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

/**
 * ADR-0012(#193)에 따라 {@code practice_intentions}는 DB 테이블이 아니라 이 싱글턴 빈 내부 힙 메모리에
 * 저장한다. 재시작 시 전부 초기화되며 다중 인스턴스 배포 간 공유되지 않는다.
 *
 * <p>저장 구조: {@code Map<userId, List<PracticeIntention>>}. 사용자별로 여러 건을 누적하며 값 리스트는
 * {@link CopyOnWriteArrayList}로 관리한다. intentionId는 전역 {@link AtomicLong} 시퀀스로 채번한다.
 *
 * <p>호출부(PracticeIntentionService)가 이미 자신이 빌린 favorite 락(사용자 단위) 안에서 이 저장소를 호출하므로
 * 여기서는 별도 잠금을 걸지 않는다.
 */
@Repository
public class PracticeIntentionRepository {

	private final Map<Long, List<PracticeIntention>> intentionsByUser = new ConcurrentHashMap<>();
	private final AtomicLong intentionIdSequence = new AtomicLong();

	public PracticeIntention save(PracticeIntention intentionWithoutId) {
		PracticeIntention intention = new PracticeIntention(
			intentionIdSequence.incrementAndGet(),
			intentionWithoutId.userId(),
			intentionWithoutId.instrumentId(),
			intentionWithoutId.quantity(),
			intentionWithoutId.stopLoss(),
			intentionWithoutId.takeProfit(),
			intentionWithoutId.createdAt());
		intentionsByUser
			.computeIfAbsent(intention.userId(), key -> new CopyOnWriteArrayList<>())
			.add(intention);
		return intention;
	}

	public List<PracticeIntention> findByUserId(Long userId) {
		return List.copyOf(intentionsByUser.getOrDefault(userId, List.of()));
	}
}
