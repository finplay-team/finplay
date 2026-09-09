package com.finplay.api.domain.education.repository;

import com.finplay.api.domain.education.model.PracticeIntention;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

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
