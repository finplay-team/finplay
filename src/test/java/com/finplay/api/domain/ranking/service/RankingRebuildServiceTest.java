package com.finplay.api.domain.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.ranking.store.RankingEntryDto;
import com.finplay.api.domain.ranking.store.RankingStore;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.test.util.ReflectionTestUtils;

class RankingRebuildServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 9, 0, 0);

	private static final String EXPECTED_CRON = "0 20 4 * * *";

	private final TradeService tradeService = mock(TradeService.class);
	private final AccountService accountService = mock(AccountService.class);
	private final RankingStore rankingStore = mock(RankingStore.class);
	private final RankingRebuildLock rankingRebuildLock = mock(RankingRebuildLock.class);

	private final RankingRebuildService rankingRebuildService = new RankingRebuildService(tradeService, accountService,
		rankingStore, rankingRebuildLock);

	@BeforeEach
	void stubLockAlwaysSucceeds() {
		when(rankingRebuildLock.tryLock(any())).thenAnswer(invocation -> Optional.of(UUID.randomUUID().toString()));
	}

	@Test
	@DisplayName("매도 이력 계좌 조회 → 계좌 배치 조회 → ZSET 전체 교체 순서로 위임한다")
	void rebuildDelegatesInOrder() {
		when(tradeService.getSoldAccountIds(Market.STOCK)).thenReturn(List.of(1L, 2L));
		when(accountService.getAccountsByIds(List.of(1L, 2L)))
			.thenReturn(List.of(account(1L, 5_000L, 10L, "alpha"), account(2L, -1_000L, 11L, "beta")));

		rankingRebuildService.rebuild(Market.STOCK);

		InOrder inOrder = inOrder(tradeService, accountService, rankingStore);
		inOrder.verify(tradeService).getSoldAccountIds(Market.STOCK);
		inOrder.verify(accountService).getAccountsByIds(List.of(1L, 2L));
		inOrder.verify(rankingStore).replaceAll(eq(Market.STOCK), anyList());
	}

	@Test
	@DisplayName("score를 accounts.realized_pnl에서 가져온다")
	void rebuildUsesRealizedPnlAsScore() {
		when(tradeService.getSoldAccountIds(Market.STOCK)).thenReturn(List.of(1L, 2L));
		when(accountService.getAccountsByIds(List.of(1L, 2L)))
			.thenReturn(List.of(account(1L, 5_000L, 10L, "alpha"), account(2L, -1_000L, 11L, "beta")));

		rankingRebuildService.rebuild(Market.STOCK);

		assertThat(capturedEntries(Market.STOCK))
			.containsExactly(new RankingEntryDto(1L, 5_000L), new RankingEntryDto(2L, -1_000L));
	}

	@Test
	@DisplayName("매도 이력이 있고 realized_pnl이 0인 계좌도 재구성 대상에 포함한다")
	void rebuildIncludesSoldAccountWithZeroRealizedPnl() {
		when(tradeService.getSoldAccountIds(Market.STOCK)).thenReturn(List.of(1L, 2L));
		when(accountService.getAccountsByIds(List.of(1L, 2L)))
			.thenReturn(List.of(account(1L, 5_000L, 10L, "alpha"), account(2L, 0L, 11L, "flat")));

		rankingRebuildService.rebuild(Market.STOCK);

		assertThat(capturedEntries(Market.STOCK))
			.as("손익 0인 계좌를 걸러내면 재구성 결과가 유실 전 랭킹과 달라진다")
			.containsExactly(new RankingEntryDto(1L, 5_000L), new RankingEntryDto(2L, 0L));
	}

	@Test
	@DisplayName("대상이 realized_pnl = 0인 계좌 하나뿐이어도 그 계좌가 그대로 실린다")
	void rebuildKeepsTheOnlyCandidateEvenWhenItsRealizedPnlIsZero() {
		when(tradeService.getSoldAccountIds(Market.STOCK)).thenReturn(List.of(2L));
		when(accountService.getAccountsByIds(List.of(2L))).thenReturn(List.of(account(2L, 0L, 11L, "flat")));

		rankingRebuildService.rebuild(Market.STOCK);

		assertThat(capturedEntries(Market.STOCK)).containsExactly(new RankingEntryDto(2L, 0L));
	}

	@Test
	@DisplayName("매도 이력 계좌가 하나도 없어도 replaceAll을 빈 목록으로 호출한다")
	void rebuildCallsReplaceAllEvenWhenNoAccountsQualify() {
		when(tradeService.getSoldAccountIds(Market.STOCK)).thenReturn(List.of());

		rankingRebuildService.rebuild(Market.STOCK);

		verify(rankingStore, times(1)).replaceAll(Market.STOCK, List.of());
	}

	@Test
	@DisplayName("rebuildAll은 모든 시장을 각각 재구성한다")
	void rebuildAllCoversEveryMarket() {
		stubEmptyForAllMarkets();

		rankingRebuildService.rebuildAll();

		for (Market market : Market.values()) {
			verify(rankingStore, times(1)).replaceAll(eq(market), anyList());
		}
	}

	@Test
	@DisplayName("한 시장이 실패해도 다른 시장 재구성을 막지 않는다")
	void rebuildAllIsolatesFailurePerMarket() {
		Market failing = Market.values()[0];
		when(tradeService.getSoldAccountIds(failing)).thenThrow(new DataAccessResourceFailureException("DB 장애"));
		for (Market market : Market.values()) {
			if (market != failing) {
				when(tradeService.getSoldAccountIds(market)).thenReturn(List.of());
			}
		}

		assertThatCode(rankingRebuildService::rebuildAll).doesNotThrowAnyException();

		verify(rankingStore, never()).replaceAll(eq(failing), anyList());
		for (Market market : Market.values()) {
			if (market != failing) {
				verify(rankingStore, times(1)).replaceAll(market, List.of());
			}
		}
	}

	@Test
	@DisplayName("계좌 배치 조회가 실패한 시장도 다른 시장 재구성을 막지 않는다")
	void rebuildAllIsolatesAccountLookupFailurePerMarket() {
		Market failing = Market.values()[0];
		for (Market market : Market.values()) {
			when(tradeService.getSoldAccountIds(market)).thenReturn(market == failing ? List.of(7L) : List.of());
		}
		when(accountService.getAccountsByIds(List.of(7L))).thenThrow(new DataAccessResourceFailureException("DB 장애"));

		assertThatCode(rankingRebuildService::rebuildAll).doesNotThrowAnyException();

		verify(rankingStore, never()).replaceAll(eq(failing), anyList());
		for (Market market : Market.values()) {
			if (market != failing) {
				verify(rankingStore, times(1)).replaceAll(market, List.of());
			}
		}
	}

	@Test
	@DisplayName("단건 rebuild는 예외를 삼키지 않고 그대로 전파한다")
	void rebuildPropagatesFailureToItsCaller() {
		when(tradeService.getSoldAccountIds(Market.STOCK)).thenThrow(new DataAccessResourceFailureException("DB 장애"));

		assertThatThrownBy(() -> rankingRebuildService.rebuild(Market.STOCK))
			.isInstanceOf(DataAccessResourceFailureException.class);

		verify(rankingStore, never()).replaceAll(eq(Market.STOCK), anyList());
	}

	@Test
	@DisplayName("락을 얻지 못하면 그 시장의 재구성을 건너뛰고 DB·Redis 어느 쪽도 호출하지 않는다")
	void rebuildSkipsWhenLockIsNotAcquired() {
		when(rankingRebuildLock.tryLock(Market.STOCK)).thenReturn(Optional.empty());

		rankingRebuildService.rebuild(Market.STOCK);

		verify(tradeService, never()).getSoldAccountIds(Market.STOCK);
		verify(accountService, never()).getAccountsByIds(anyList());
		verify(rankingStore, never()).replaceAll(eq(Market.STOCK), anyList());
	}

	@Test
	@DisplayName("락을 얻지 못하면 어느 시장에서 건너뛰었는지 INFO로 남긴다")
	void rebuildLogsWhichMarketWasSkippedWhenLockIsNotAcquired() {
		when(rankingRebuildLock.tryLock(Market.STOCK)).thenReturn(Optional.empty());

		List<ILoggingEvent> logs = capturingLogs(() -> rankingRebuildService.rebuild(Market.STOCK));

		assertThat(logs)
			.extracting(ILoggingEvent::getFormattedMessage)
			.anyMatch(message -> message.contains("건너뜁니다") && message.contains("STOCK"));
	}

	@Test
	@DisplayName("스킵 로그는 원인을 한쪽으로 단정하지 않고 다른 인스턴스 처리 중·Redis 장애 둘 다 언급한다")
	void rebuildSkipLogDoesNotAssertASingleCause() {
		when(rankingRebuildLock.tryLock(Market.STOCK)).thenReturn(Optional.empty());

		List<ILoggingEvent> logs = capturingLogs(() -> rankingRebuildService.rebuild(Market.STOCK));

		assertThat(logs)
			.extracting(ILoggingEvent::getFormattedMessage)
			.anyMatch(message -> message.contains("다른 인스턴스가 처리 중이거나 Redis 문제로"));
	}

	@Test
	@DisplayName("한 시장이 락 경합으로 건너뛰어도 다른 시장 재구성은 막지 않는다")
	void rebuildAllSkipsOnlyTheMarketThatFailsToAcquireTheLock() {
		Market locked = Market.values()[0];
		stubEmptyForAllMarkets();
		when(rankingRebuildLock.tryLock(locked)).thenReturn(Optional.empty());

		rankingRebuildService.rebuildAll();

		verify(rankingStore, never()).replaceAll(eq(locked), anyList());
		for (Market market : Market.values()) {
			if (market != locked) {
				verify(rankingStore, times(1)).replaceAll(market, List.of());
			}
		}
	}

	@Test
	@DisplayName("재구성이 끝나면 tryLock이 돌려준 토큰으로 같은 시장의 락을 해제한다")
	void rebuildUnlocksWithTheTokenReturnedByTryLock() {
		String token = "held-token";
		when(rankingRebuildLock.tryLock(Market.STOCK)).thenReturn(Optional.of(token));
		when(tradeService.getSoldAccountIds(Market.STOCK)).thenReturn(List.of());

		rankingRebuildService.rebuild(Market.STOCK);

		verify(rankingRebuildLock).unlock(Market.STOCK, token);
	}

	@Test
	@DisplayName("재구성 도중 예외가 나도 락을 해제한다")
	void rebuildUnlocksEvenWhenReconstructionFails() {
		String token = "held-token";
		when(rankingRebuildLock.tryLock(Market.STOCK)).thenReturn(Optional.of(token));
		when(tradeService.getSoldAccountIds(Market.STOCK)).thenThrow(new DataAccessResourceFailureException("DB 장애"));

		assertThatThrownBy(() -> rankingRebuildService.rebuild(Market.STOCK))
			.isInstanceOf(DataAccessResourceFailureException.class);

		verify(rankingRebuildLock).unlock(Market.STOCK, token);
	}

	@Test
	@DisplayName("기동 훅과 주기 배치가 같은 재구성 경로를 탄다")
	void bothTriggersRunTheSameRebuildPath() {
		stubEmptyForAllMarkets();

		rankingRebuildService.rebuildOnStartup();
		rankingRebuildService.rebuildOnSchedule();

		for (Market market : Market.values()) {
			verify(rankingStore, times(2)).replaceAll(eq(market), anyList());
		}
	}

	@Test
	@DisplayName("기동 훅이 ApplicationReadyEvent에 걸려 있다")
	void startupHookListensToApplicationReadyEvent() throws NoSuchMethodException {
		EventListener listener = RankingRebuildService.class.getMethod("rebuildOnStartup")
			.getAnnotation(EventListener.class);

		assertThat(listener).isNotNull();
		assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
	}

	@Test
	@DisplayName("크론 값을 코드에 박지 않고 ranking.rebuild.cron 프로퍼티를 참조한다")
	void scheduleReferencesTheConfiguredCronProperty() throws NoSuchMethodException {
		assertThat(schedule().cron()).isEqualTo("${ranking.rebuild.cron}");
	}

	@Test
	@DisplayName("주기 배치에 zone = \"Asia/Seoul\"이 붙어 있다")
	void scheduleDeclaresSeoulZone() throws NoSuchMethodException {
		assertThat(schedule().zone()).isEqualTo("Asia/Seoul");
	}

	@Test
	@DisplayName("기대 크론이 매일 04:20에 한 번 돈다")
	void expectedCronRunsDailyAt0420() {
		LocalDateTime next = CronExpression.parse(EXPECTED_CRON).next(LocalDateTime.of(2026, 8, 9, 0, 0));

		assertThat(next).isEqualTo(LocalDateTime.of(2026, 8, 9, 4, 20));
	}

	@Test
	@DisplayName("계좌 배치 조회를 ZADD와 같은 청크 크기로 나눠 부르고 순서를 보존한다")
	void rebuildSplitsAccountLookupIntoChunksOfTheSameSizeAsZadd() {
		int chunkSize = RankingStore.REBUILD_CHUNK_SIZE;
		List<Long> accountIds = new ArrayList<>();
		for (long id = 1; id <= chunkSize + 1L; id++) {
			accountIds.add(id);
		}
		List<Long> firstChunk = List.copyOf(accountIds.subList(0, chunkSize));
		List<Long> secondChunk = List.copyOf(accountIds.subList(chunkSize, accountIds.size()));
		when(tradeService.getSoldAccountIds(Market.STOCK)).thenReturn(accountIds);
		when(accountService.getAccountsByIds(firstChunk)).thenReturn(List.of(account(1L, 5_000L, 10L, "alpha")));
		when(accountService.getAccountsByIds(secondChunk)).thenReturn(List.of(account(2L, -1_000L, 11L, "beta")));

		rankingRebuildService.rebuild(Market.STOCK);

		ArgumentCaptor<List<Long>> captor = ArgumentCaptor.captor();
		verify(accountService, times(2)).getAccountsByIds(captor.capture());
		assertThat(captor.getAllValues())
			.as("두 번째 청크가 통째로 붙으면 IN 절 한계를 그대로 맞는다")
			.containsExactly(firstChunk, secondChunk);
		assertThat(capturedEntries(Market.STOCK))
			.containsExactly(new RankingEntryDto(1L, 5_000L), new RankingEntryDto(2L, -1_000L));
	}

	@Test
	@DisplayName("ZSET 교체가 실패한 tick에서는 완료 INFO를 남기지 않는다")
	void rebuildDoesNotLogCompletionWhenReplaceAllFails() {
		when(tradeService.getSoldAccountIds(Market.STOCK)).thenReturn(List.of(1L));
		when(accountService.getAccountsByIds(List.of(1L))).thenReturn(List.of(account(1L, 5_000L, 10L, "alpha")));
		when(rankingStore.replaceAll(eq(Market.STOCK), anyList())).thenReturn(false);

		List<ILoggingEvent> logs = capturingLogs(() -> rankingRebuildService.rebuild(Market.STOCK));

		assertThat(logs)
			.as("실패한 tick의 완료 로그는 운영에서 실패를 성공으로 읽게 만든다")
			.extracting(ILoggingEvent::getFormattedMessage)
			.noneMatch(message -> message.contains("랭킹 재구성 완료"));
	}

	@Test
	@DisplayName("ZSET 교체가 성공한 tick에서는 완료 INFO를 남긴다")
	void rebuildLogsCompletionWhenReplaceAllSucceeds() {
		when(tradeService.getSoldAccountIds(Market.STOCK)).thenReturn(List.of(1L));
		when(accountService.getAccountsByIds(List.of(1L))).thenReturn(List.of(account(1L, 5_000L, 10L, "alpha")));
		when(rankingStore.replaceAll(eq(Market.STOCK), anyList())).thenReturn(true);

		List<ILoggingEvent> logs = capturingLogs(() -> rankingRebuildService.rebuild(Market.STOCK));

		assertThat(logs)
			.extracting(ILoggingEvent::getFormattedMessage)
			.anyMatch(message -> message.contains("랭킹 재구성 완료") && message.contains("대상 계좌 수=1"));
	}

	@Test
	@DisplayName("완료 INFO에 소요 시간(ms)을 함께 남긴다")
	void rebuildLogsElapsedMillisecondsOnCompletion() {
		when(tradeService.getSoldAccountIds(Market.STOCK)).thenReturn(List.of(1L));
		when(accountService.getAccountsByIds(List.of(1L))).thenReturn(List.of(account(1L, 5_000L, 10L, "alpha")));
		when(rankingStore.replaceAll(eq(Market.STOCK), anyList())).thenReturn(true);

		List<ILoggingEvent> logs = capturingLogs(() -> rankingRebuildService.rebuild(Market.STOCK));

		assertThat(logs)
			.extracting(ILoggingEvent::getFormattedMessage)
			.anyMatch(message -> message.contains("랭킹 재구성 완료") && message.matches(".*소요=\\d+ms.*"));
	}

	private static List<ILoggingEvent> capturingLogs(Runnable action) {
		Logger logger = (Logger)LoggerFactory.getLogger(RankingRebuildService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level originalLevel = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);
		try {
			action.run();
			return List.copyOf(appender.list);
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(originalLevel);
			appender.stop();
		}
	}

	private Scheduled schedule() throws NoSuchMethodException {
		return RankingRebuildService.class.getMethod("rebuildOnSchedule").getAnnotation(Scheduled.class);
	}

	private void stubEmptyForAllMarkets() {
		for (Market market : Market.values()) {
			when(tradeService.getSoldAccountIds(market)).thenReturn(List.of());
		}
	}

	@SuppressWarnings("unchecked")
	private List<RankingEntryDto> capturedEntries(Market market) {
		ArgumentCaptor<List<RankingEntryDto>> captor = ArgumentCaptor.forClass(List.class);
		verify(rankingStore).replaceAll(eq(market), captor.capture());
		return captor.getValue();
	}

	private Account account(Long accountId, long realizedPnl, Long userId, String nickname) {
		User user = User.create(nickname + "@finplay.com", "password-hash", nickname, NOW);
		ReflectionTestUtils.setField(user, "id", userId);

		Account account = Account.create(user, Market.STOCK, NOW);
		ReflectionTestUtils.setField(account, "id", accountId);
		ReflectionTestUtils.setField(account, "realizedPnl", realizedPnl);
		return account;
	}
}
