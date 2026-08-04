// 랭킹 점수 갱신·조회를 담당하는 서비스 (현재는 RankingEventListener가 참조할 최소 스텁, 다음 작업 항목에서 실제 구현으로 교체)
package com.finplay.api.ranking.service;

import org.springframework.stereotype.Service;

@Service
public class RankingService {

	// TODO: 다음 작업 항목(RANK-001 서비스 구현)에서 AccountRepository 조회 + RankingStore.addScoreWithRetry 호출로 교체한다.
	public void refreshScore(Long accountId) {}
}
