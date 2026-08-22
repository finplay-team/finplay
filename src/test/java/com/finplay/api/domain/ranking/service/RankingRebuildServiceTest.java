// mock 협력 객체로 RankingRebuildService의 위임 순서·score 출처·시장별 예외 격리·두 트리거 동일 경로를 검증한다.
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

	// plan.md "Decision Gate 확정" — 값의 정본은 application.yml의 ranking.rebuild.cron이고 여기선 기대값이다.
	//
	// 이 상수는 yml을 읽지 않으므로 아래 expectedCronRunsDailyAt0420은 "이 문자열이 04:20을 뜻한다"까지만
	// 보장한다. yml의 실제 값이 이 상수와 갈리는 회귀는 RankingRebuildCronPropertiesTest가 잡는다 — 그쪽은
	// spring.config.additional-location을 비우고 application.yml을 직접 읽는다.
	private static final String EXPECTED_CRON = "0 20 4 * * *";

	private final TradeService tradeService = mock(TradeService.class);
	private final AccountService accountService = mock(AccountService.class);
	private final RankingStore rankingStore = mock(RankingStore.class);
	private final RankingRebuildLock rankingRebuildLock = mock(RankingRebuildLock.class);

	private final RankingRebuildService rankingRebuildService = new RankingRebuildService(tradeService, accountService,
		rankingStore, rankingRebuildLock);

	// 이 클래스의 관심사는 락 자체(RankingRebuildLockTest·RankingRebuildLockConcurrencyIntegrationTest 몫)가
	// 아니라 락을 얻었을 때의 위임 순서·score 출처·예외 격리다. 그래서 기본적으로는 항상 락을 얻는다고
	// 가정하고, 락을 얻지 못했을 때의 동작만 별도 테스트(rebuildSkipsWhenLockIsNotAcquired 등)에서 개별
	// 스텁으로 뒤집는다.
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

	// score의 출처가 accounts.realized_pnl임을 캡처로 못박는다 — 다른 값(보유 평가액 등)으로 바뀌면
	// 재구성 결과가 유실 전 랭킹과 달라진다.
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

	// 이 정책(대상 판정은 매도 이력, score는 realized_pnl)의 존재 이유다. realized_pnl != 0으로 대상을
	// 고르면 매도했지만 손익이 정확히 0인 계좌가 빠져 재구성 결과가 유실 전과 달라진다.
	//
	// contains(...)가 아니라 containsExactly(...)로 본다. 손익 0인 계좌 하나만 "들어 있는지" 보면 서비스가
	// 그 계좌를 남기면서 다른 계좌를 떨어뜨리는 회귀는 통과해 버린다 — 이 단정이 지켜야 하는 것은 "0도
	// 포함된다"가 아니라 "매도 이력 목록이 손익값과 무관하게 그대로 보존된다"이다. 순서까지 고정하면
	// 조회 순서를 뒤집는 변경도 함께 드러난다.
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

	// 위 단정이 "서비스에 필터가 없어서" 자동으로 통과하는 것이 아님을 반대 방향으로 고정한다. 손익이 0인
	// 계좌 하나뿐인 시장에서도 그 계좌가 그대로 실린다 — realized_pnl != 0 필터가 어디에 생기든 여기서는
	// 빈 목록이 되어 즉시 드러난다(0건이어도 replaceAll은 불리므로 호출 유무로는 구분되지 않는다).
	@Test
	@DisplayName("대상이 realized_pnl = 0인 계좌 하나뿐이어도 그 계좌가 그대로 실린다")
	void rebuildKeepsTheOnlyCandidateEvenWhenItsRealizedPnlIsZero() {
		when(tradeService.getSoldAccountIds(Market.STOCK)).thenReturn(List.of(2L));
		when(accountService.getAccountsByIds(List.of(2L))).thenReturn(List.of(account(2L, 0L, 11L, "flat")));

		rankingRebuildService.rebuild(Market.STOCK);

		assertThat(capturedEntries(Market.STOCK)).containsExactly(new RankingEntryDto(2L, 0L));
	}

	// 대상 0건이어도 replaceAll을 부른다 — 부르지 않으면 원장에서 사라진 계좌가 ZSET에 영원히 남는다
	// (0건 경계에서 본 키를 DEL하는 것은 RankingStore.replaceAll의 책임이다).
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

	// 한 시장의 DB 조회가 터져도 다음 시장은 계속 돈다. replaceAll은 이미 예외를 삼키지만 그 앞의 조회는
	// 예외를 던질 수 있어 rebuildAll 루프 안에서 시장별로 감싼다.
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

	// 위 격리 테스트는 첫 시장의 tradeService 조회만 터뜨린다 — try/catch가 tradeService 호출 한 줄만 감싸도
	// 통과한다. 계좌 배치 조회(둘째 단계)가 터지는 경우도 같은 루프 안에서 격리되는지 따로 본다.
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

	// 예외를 삼키는 것은 rebuildAll의 루프이지 rebuild 자체가 아니다. 단건 rebuild까지 삼키게 바뀌면 앞으로
	// 붙을 수동/통합 호출자가 실패를 성공으로 읽는다 — 그 경계를 여기서 고정한다.
	@Test
	@DisplayName("단건 rebuild는 예외를 삼키지 않고 그대로 전파한다")
	void rebuildPropagatesFailureToItsCaller() {
		when(tradeService.getSoldAccountIds(Market.STOCK)).thenThrow(new DataAccessResourceFailureException("DB 장애"));

		assertThatThrownBy(() -> rankingRebuildService.rebuild(Market.STOCK))
			.isInstanceOf(DataAccessResourceFailureException.class);

		verify(rankingStore, never()).replaceAll(eq(Market.STOCK), anyList());
	}

	// 이슈 #539 — 블루-그린 두 인스턴스가 같은 시장을 동시에 재구성하지 못하게 막는 락. 다른 인스턴스가 이미
	// 이 시장을 처리 중이면(tryLock이 빈 값) DB·Redis 어느 쪽도 건드리지 않고 조용히 건너뛴다.
	@Test
	@DisplayName("락을 얻지 못하면 그 시장의 재구성을 건너뛰고 DB·Redis 어느 쪽도 호출하지 않는다")
	void rebuildSkipsWhenLockIsNotAcquired() {
		when(rankingRebuildLock.tryLock(Market.STOCK)).thenReturn(Optional.empty());

		rankingRebuildService.rebuild(Market.STOCK);

		verify(tradeService, never()).getSoldAccountIds(Market.STOCK);
		verify(accountService, never()).getAccountsByIds(anyList());
		verify(rankingStore, never()).replaceAll(eq(Market.STOCK), anyList());
	}

	// 위 테스트는 실행 자체가 건너뛰는지만 본다 — 그 판단이 조용히 삼켜지지 않고 운영에서 관찰 가능한지는
	// 로그로 고정한다(다른 인스턴스가 처리 중이라는 원인까지 남아야 운영이 오탐과 구분할 수 있다).
	@Test
	@DisplayName("락을 얻지 못하면 어느 시장에서 건너뛰었는지 INFO로 남긴다")
	void rebuildLogsWhichMarketWasSkippedWhenLockIsNotAcquired() {
		when(rankingRebuildLock.tryLock(Market.STOCK)).thenReturn(Optional.empty());

		List<ILoggingEvent> logs = capturingLogs(() -> rankingRebuildService.rebuild(Market.STOCK));

		assertThat(logs)
			.extracting(ILoggingEvent::getFormattedMessage)
			.anyMatch(message -> message.contains("건너뜁니다") && message.contains("STOCK"));
	}

	// 락 범위는 시장 단위다 — 한 시장에서 락을 얻지 못해도 다른 시장은 그대로 재구성된다.
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

	// tryLock이 반환한 토큰을 그대로 unlock에 넘긴다 — 다른 토큰을 넘기면 RedisLock의 check-then-delete가
	// 항상 NOT_HELD로 실패해 락이 TTL 동안 계속 잠긴 채로 남는다.
	@Test
	@DisplayName("재구성이 끝나면 tryLock이 돌려준 토큰으로 같은 시장의 락을 해제한다")
	void rebuildUnlocksWithTheTokenReturnedByTryLock() {
		String token = "held-token";
		when(rankingRebuildLock.tryLock(Market.STOCK)).thenReturn(Optional.of(token));
		when(tradeService.getSoldAccountIds(Market.STOCK)).thenReturn(List.of());

		rankingRebuildService.rebuild(Market.STOCK);

		verify(rankingRebuildLock).unlock(Market.STOCK, token);
	}

	// 락 해제는 finally에 있어야 한다 — 그렇지 않으면 DB 조회 실패가 락을 TTL 동안 계속 잠긴 채로 남겨, 다음
	// 재구성 기회(기동 또는 익일 04:20)까지 그 시장은 재구성될 수 없다.
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

	// 기동 훅과 주기 배치가 같은 rebuildAll() 경로를 탄다 — 한쪽만 고쳐 두 트리거가 갈리는 것을 막는다.
	@Test
	@DisplayName("기동 훅과 주기 배치가 같은 재구성 경로를 탄다")
	void bothTriggersRunTheSameRebuildPath() {
		stubEmptyForAllMarkets();

		rankingRebuildService.rebuildOnStartup();
		rankingRebuildService.rebuildOnSchedule();

		// 두 트리거가 각각 모든 시장을 재구성했으므로 시장당 정확히 2회다.
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

	// 크론 값을 코드에 박지 않는다 — 운영 조정은 application.yml에서 한다.
	@Test
	@DisplayName("크론 값을 코드에 박지 않고 ranking.rebuild.cron 프로퍼티를 참조한다")
	void scheduleReferencesTheConfiguredCronProperty() throws NoSuchMethodException {
		assertThat(schedule().cron()).isEqualTo("${ranking.rebuild.cron}");
	}

	// zone을 빠뜨리면 배포 JVM 기본 타임존이 UTC라 04:20 배치가 KST 13:20에 돈다. 예외도 로그도 남지 않아
	// 애노테이션을 읽어야만 알 수 있다(FeedbackBatchScheduleTest와 같은 형태).
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

	// 기동 훅의 @Order 계약 테스트가 있었으나 지웠다(PR #284 재리뷰). readiness(ACCEPTING_TRAFFIC)는
	// ApplicationReadyEvent 디스패치가 전부 끝난 뒤에 발행돼 리스너 간 @Order로 앞당길 수 없고, 애초에
	// @Order가 없어도 ApplicationListenerMethodAdapter가 LOWEST_PRECEDENCE를 돌려주므로 값도 기본값과 같았다.
	// 고정할 동작이 없어 애노테이션과 함께 제거했다 — 남은 readiness 지연은 spec.md "알려진 한계"에 있다.

	// 계좌 배치 조회를 ZADD와 같은 청크 크기로 나눈다(PR #284 리뷰). 나누지 않으면 계좌 수가 늘 때 IN 절
	// 파라미터·패킷이 먼저 한계에 닿고, 그 예외는 rebuildAll의 시장별 catch에 삼켜져 로그로만 남는다.
	// 상수를 여기서 하드코딩하지 않고 RankingStore.REBUILD_CHUNK_SIZE를 참조해 두 크기가 갈리는 회귀도 잡는다.
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
		// 청크를 나눠도 결과는 조회 순서대로 이어 붙는다 — 순서가 뒤집히면 랭킹 자체는 score 정렬이라 보이지
		// 않지만, 경계 동점 처리·회귀 단정이 조회 순서를 전제로 하고 있다.
		assertThat(capturedEntries(Market.STOCK))
			.containsExactly(new RankingEntryDto(1L, 5_000L), new RankingEntryDto(2L, -1_000L));
	}

	// replaceAll은 예외를 삼키므로(기동 훅·스케줄러를 지키기 위한 계약) 반환값이 유일한 성공 판정 근거다.
	// Redis가 죽어 교체가 실패한 tick에서 "완료" INFO가 남으면 로그만 보는 사람이 실패를 성공으로 읽는다.
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

	// 위 단정이 "완료 로그가 아예 사라져서" 통과하는 것이 아님을 반대 방향으로 고정한다.
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

	// 로그가 성공/실패 구분의 유일한 외부 관찰점이라 로거에 임시 appender를 붙인다 (CryptoWatchLockTest와 같은 방식).
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
