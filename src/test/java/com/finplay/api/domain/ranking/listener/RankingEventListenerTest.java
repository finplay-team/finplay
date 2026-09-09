package com.finplay.api.domain.ranking.listener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.finplay.api.domain.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.domain.ranking.service.RankingService;
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
