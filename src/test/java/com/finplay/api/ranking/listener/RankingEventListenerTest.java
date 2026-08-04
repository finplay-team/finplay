// RankingService.refreshScore가 예외를 던져도 리스너 밖으로 전파되지 않는지 검증하는 단위 테스트다.
package com.finplay.api.ranking.listener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.finplay.api.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.ranking.service.RankingService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class RankingEventListenerTest {

	private final RankingService rankingService = mock(RankingService.class);
	private final RankingEventListener rankingEventListener = new RankingEventListener(rankingService);

	@Test
	void onRealizedPnlUpdatedSwallowsDataAccessExceptionFromRefreshScore() {
		Long accountId = 10L;
		doThrow(new DataAccessResourceFailureException("db down"))
			.when(rankingService)
			.refreshScore(accountId);

		assertThatCode(() -> rankingEventListener.onRealizedPnlUpdated(new RealizedPnlUpdatedEvent(accountId)))
			.doesNotThrowAnyException();

		verify(rankingService).refreshScore(accountId);
	}
}
