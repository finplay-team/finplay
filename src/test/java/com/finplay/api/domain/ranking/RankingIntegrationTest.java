// 매도 체결→커밋→랭킹 반영 전체 흐름을 Testcontainers MySQL+Redis로 검증하는 통합 테스트다.
package com.finplay.api.domain.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import com.finplay.api.domain.ranking.dto.response.MyRankingResponse;
import com.finplay.api.domain.ranking.dto.response.RankingListItemResponse;
import com.finplay.api.domain.ranking.dto.response.RankingListResponse;
import com.finplay.api.domain.ranking.listener.RankingEventListener;
import com.finplay.api.domain.ranking.service.RankingService;
import com.finplay.api.domain.ranking.store.RankingStore;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

// after-commit(AFTER_COMMIT) 리스너가 실제로 커밋된 뒤에만 동작하는지를 검증해야 하므로, 이 테스트 클래스는
// (다른 통합테스트들과 달리) @Transactional을 붙이지 않는다 — 클래스 단위 @Transactional은 테스트 메서드 전체를
// 하나의 트랜잭션으로 감싸 기본적으로 롤백시키는데, 그 안에서 서비스가 호출한 @Transactional 메서드는 같은
// 물리 트랜잭션에 참여(REQUIRED)해 실제 커밋이 한 번도 일어나지 않는다 — AFTER_COMMIT 콜백 자체가 발동하지
// 않아 이 스펙의 핵심 동작(커밋 이후에만 반영)을 검증할 수 없다. 대신 CRYPTO 시장만 사용해 STOCK 경로가 요구하는
// Instrument·StockReplaySession·StockCandle 신규 커밋을 피한다 — 이 테이블들은 InstrumentRepositoryTest 등의
// 절대개수 단정과 얽혀 있고(2026-07-30 agent-mistakes.md), 실행 순서가 항상 안전하다고 보장할 수 없다
// (CandleQueryServiceIntegrationTest 주석의 실제 재현 사례 참고). CRYPTO는 기존 시드 종목을 재사용하고 가격은
// Redis(PriceStore)에만 쓰므로 MySQL 시드 테이블을 건드리지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RankingIntegrationTest {

	private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private Clock clock;

	@Autowired
	private RankingService rankingService;

	@Autowired
	private RankingEventListener rankingEventListener;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@MockitoSpyBean
	private RankingStore rankingStore;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// 이 테스트가 만든 계좌·회원 id. @Transactional을 못 붙이는 클래스라(아래 클래스 주석) 매도 체결이 공유
	// MySQL에 실제로 커밋되는데, 지금까지 tearDown이 Redis 키만 지워 CRYPTO 매도 이력이 남아 있었다. 그러면
	// 뒤에 도는 다른 테스트가 "이 시장엔 매도 이력이 없다"를 단정할 수 없다 — 이슈 #279 작업 중 @DataJpaTest
	// 프로브로 실측 확인했고, 실제로 그 단정 하나를 포기해야 했다(TradeRepositoryTest 주석 참고).
	// JournalIntegrationTest가 같은 이유로 쓰는 명시적 정리 방식을 따른다(agent-mistakes.md 2026-07-30).
	private final List<Long> createdAccountIds = new ArrayList<>();
	private final List<Long> createdUserIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		cleanRankingKeys();
	}

	@AfterEach
	void tearDown() {
		reset(rankingStore);
		cleanRankingKeys();
		cleanCommittedLedger();
	}

	// 이 테스트가 만든 계좌에 딸린 원장을 FK 자식 → 부모 순서로 지운다. 종목(instruments)은 시드 데이터를
	// 그대로 쓰므로 건드리지 않는다 — 지우는 기준은 "이 클래스가 만든 계좌"다.
	//
	// trades를 참조하는 FK 중 trade_feedbacks(V13)·buy_trade_journals(V15)·sell_trade_journals(V17)는 지우지
	// 않는다. 이 클래스가 그 행을 만들지 않기 때문이다 — 이 클래스에서 저널이나 매도 피드백을 만들게 되면
	// 아래 "delete from trades"가 FK 위반으로 터지므로 그때 함께 추가해야 한다.
	private void cleanCommittedLedger() {
		if (!createdAccountIds.isEmpty()) {
			String accountIdIn = createdAccountIds.stream().map(String::valueOf).collect(Collectors.joining(","));
			jdbcTemplate.update("delete from trade_allocations where sell_trade_id in "
				+ "(select id from trades where account_id in (" + accountIdIn + "))");
			jdbcTemplate.update("delete from holding_lots where holding_id in "
				+ "(select id from holdings where account_id in (" + accountIdIn + "))");
			jdbcTemplate.update("delete from holdings where account_id in (" + accountIdIn + ")");
			jdbcTemplate.update("delete from trades where account_id in (" + accountIdIn + ")");
			jdbcTemplate.update("delete from orders where account_id in (" + accountIdIn + ")");
			createdAccountIds.clear();
		}
		// 계좌 삭제를 user_id 기준으로만 한다 — 계좌 없이 회원만 만드는 헬퍼가 생겨도 그 회원이 남지 않도록
		// 두 블록의 조건을 분리해 둔다(계좌 id 기준 삭제와 중복 실행되지 않는다).
		if (!createdUserIds.isEmpty()) {
			String userIdIn = createdUserIds.stream().map(String::valueOf).collect(Collectors.joining(","));
			jdbcTemplate.update("delete from accounts where user_id in (" + userIdIn + ")");
			jdbcTemplate.update("delete from users where id in (" + userIdIn + ")");
			createdUserIds.clear();
		}
	}

	// 시나리오 1: 실제 매수 후 매도 체결 API를 호출하면(리스너가 동기 실행이므로 응답 반환 시점엔 이미 반영됨),
	// GET /api/rankings 조회 시 해당 계좌가 올바른 realizedPnl로 나타난다.
	@Test
	void sellExecutionCommitsAndAppearsInRankingByResponseTime() throws Exception {
		User user = createUser("rank-sell");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();

		seedCryptoPrice(instrument, new BigDecimal("50000000"));
		performOrder(accessToken, buyRequest(instrument.getId(), "0.02"))
			.andExpect(status().isCreated());

		// 매수보다 높은 가격으로 갱신한 뒤 절반만 매도해 양의 실현손익을 만든다.
		seedCryptoPrice(instrument, new BigDecimal("80000000"));
		String sellBody = performOrder(accessToken, sellRequest(instrument.getId(), "0.01"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		OrderResponse sellResponse = objectMapper.readValue(sellBody, OrderResponse.class);
		assertThat(sellResponse.realizedPnl()).isNotNull();

		RankingListResponse rankings = getRankings(accessToken, "CRYPTO", null);

		assertThat(rankings.market()).isEqualTo("CRYPTO");
		assertThat(rankings.content()).hasSize(1);
		RankingListItemResponse item = rankings.content().get(0);
		assertThat(item.rank()).isEqualTo(1);
		assertThat(item.nickname()).isEqualTo(user.getNickname());
		assertThat(item.realizedPnl()).isEqualTo(sellResponse.realizedPnl());
	}

	// 시나리오 2: 같은 계좌의 실현손익이 두 번 갱신된 뒤(최종값이 DB 정본), 리스너를 순서와 무관하게 여러 번
	// 호출해도(이벤트 도착 순서 역전 시뮬레이션) 최종 ZSET score는 DB의 최신 realized_pnl과 일치해야 한다 —
	// 이벤트가 accountId만 싣고 처리 시점에 DB를 다시 조회하는 설계(plan.md 동시성 경합 Decision Gate) 덕분이다.
	@Test
	void eventOrderReversalStillConvergesToLatestDbRealizedPnl() {
		User user = createUser("rank-reorder");
		Account account = createAccount(user);

		addRealizedPnlAndCommit(account.getId(), 100L);
		rankingEventListener.onRealizedPnlUpdated(new RealizedPnlUpdatedEvent(account.getId()));
		assertThat(scoreOf("CRYPTO", account.getId())).isEqualTo(100.0);

		long finalRealizedPnl = addRealizedPnlAndCommit(account.getId(), 50L); // DB 최종값 150.

		// "순서를 바꿔 두 번 호출" — 최신 커밋 이후 리스너를 반복 호출해도(어느 순서로 호출했다고 가정하든) DB를
		// 다시 읽으므로 결과가 달라지지 않는다는 것을 확인한다.
		rankingEventListener.onRealizedPnlUpdated(new RealizedPnlUpdatedEvent(account.getId()));
		rankingEventListener.onRealizedPnlUpdated(new RealizedPnlUpdatedEvent(account.getId()));

		assertThat(finalRealizedPnl).isEqualTo(150L);
		assertThat(scoreOf("CRYPTO", account.getId())).isEqualTo(150.0);
	}

	// 시나리오 2-1(PR #196 리뷰 지적, 권장이지만 핵심 설계 오류): 위 시나리오는 리스너를 트랜잭션 없는 테스트
	// 스레드에서 직접 호출해 refreshScore(REQUIRED)가 항상 새 트랜잭션을 여니 이 문제를 잡아내지 못한다.
	// AFTER_COMMIT 콜백은 원래 매도 트랜잭션의 EntityManager가 아직 스레드에 바인딩된 시점에 실행되므로,
	// refreshScore를 "이미 활성 트랜잭션이 있고 그 트랜잭션의 영속성 컨텍스트가 계좌를 옛 값으로 캐시해 둔"
	// 상태에서 호출해 재현한다. REQUIRED였다면 그 1차 캐시의 옛 값을 그대로 반환해 이 테스트가 실패했을 것이다.
	@Test
	void refreshScoreReadsLatestDbValueEvenWhenCallerHasStalePersistenceContext() {
		User user = createUser("rank-stale-pc");
		Account account = createAccount(user);
		addRealizedPnlAndCommit(account.getId(), 100L);

		TransactionTemplate outerTx = new TransactionTemplate(transactionManager);
		outerTx.executeWithoutResult(status -> {
			// 바깥 트랜잭션의 영속성 컨텍스트에 realizedPnl=100인 계좌를 먼저 적재해 1차 캐시에 남긴다.
			Account cached = accountRepository.findById(account.getId()).orElseThrow();
			assertThat(cached.getRealizedPnl()).isEqualTo(100L);

			// 완전히 별도(REQUIRES_NEW)의 트랜잭션에서 DB 값을 200으로 갱신하고 즉시 커밋한다 — 바깥 트랜잭션의
			// 1차 캐시는 이 변경을 모른 채 여전히 realizedPnl=100인 엔티티를 들고 있다.
			updateRealizedPnlInNewTransactionAndCommit(account.getId(), 200L);

			// 바깥 트랜잭션이 아직 스레드에 바인딩된 채로 refreshScore를 호출한다 — AFTER_COMMIT 리스너가 원래
			// 매도 트랜잭션에 참여하는 상황과 동일한 조건이다. REQUIRES_NEW라면 이 바깥 트랜잭션과 무관하게
			// 새 영속성 컨텍스트로 DB를 다시 읽어 최신값(200)을 가져온다.
			rankingService.refreshScore(account.getId());
		});

		assertThat(scoreOf("CRYPTO", account.getId())).isEqualTo(200.0);
	}

	// 시나리오 2-2(이슈 #270): 위 두 시나리오는 rankingEventListener.onRealizedPnlUpdated(...) 또는
	// rankingService.refreshScore(...)를 테스트 코드에서 직접 호출한다 — 리스너 등록·
	// @TransactionalEventListener(AFTER_COMMIT) phase·refreshScore가 실제로 프록시를 거쳐 호출되는지
	// (REQUIRES_NEW가 실제로 걸리는지) 자체가 깨지는 회귀는 그 직접 호출 경로로는 잡아내지 못한다 — 리스너를
	// 직접 부르면 이 배선 전체가 우회된다. 이 시나리오는 eventPublisher.publishEvent만 호출하고 트랜잭션을
	// 커밋해, Spring이 실제로 그 콜백을 발동시키는지까지 확인한다.
	@Test
	void afterCommitListenerAppliesLatestDbValueWhenEventPublishedThroughRealTransactionalWiring() {
		User user = createUser("rank-real-wiring");
		Account account = createAccount(user);
		addRealizedPnlAndCommit(account.getId(), 100L);

		TransactionTemplate outerTx = new TransactionTemplate(transactionManager);
		outerTx.executeWithoutResult(status -> {
			// 바깥 트랜잭션의 영속성 컨텍스트에 realizedPnl=100인 계좌를 먼저 적재해 1차 캐시에 남긴다 — 리스너
			// 등록이 REQUIRED로 잘못 바뀌는 회귀가 생기면 이 옛 값을 그대로 반영해 아래 단정이 실패한다.
			Account cached = accountRepository.findById(account.getId()).orElseThrow();
			assertThat(cached.getRealizedPnl()).isEqualTo(100L);

			updateRealizedPnlInNewTransactionAndCommit(account.getId(), 300L);

			// 리스너 메서드를 직접 부르지 않는다 — 실제 이벤트 발행만 한다. 이 executeWithoutResult 블록이
			// 끝나 바깥 트랜잭션이 커밋되면, 그 시점에야 Spring이 등록된 @TransactionalEventListener(AFTER_COMMIT)를
			// 실제로 호출한다.
			eventPublisher.publishEvent(new RealizedPnlUpdatedEvent(account.getId()));
		});

		assertThat(scoreOf("CRYPTO", account.getId())).isEqualTo(300.0);
	}

	// account.addRealizedPnl(...)을 완전히 새로운(REQUIRES_NEW) 트랜잭션에서 커밋한다 — 호출한 쪽의 바깥
	// 트랜잭션(및 그 1차 캐시)과 격리된 별도 갱신을 재현하기 위한 헬퍼다.
	private void updateRealizedPnlInNewTransactionAndCommit(Long accountId, long newRealizedPnl) {
		TransactionTemplate newTx = new TransactionTemplate(transactionManager);
		newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		newTx.executeWithoutResult(status -> {
			Account account = accountRepository.findById(accountId).orElseThrow();
			long delta = newRealizedPnl - account.getRealizedPnl();
			account.addRealizedPnl(delta);
			accountRepository.saveAndFlush(account);
		});
	}

	// 시나리오 3: 매도 요청이 검증 실패(보유수량 부족)로 커밋되지 않으면 ranking:{market} ZSET에 해당 계좌가
	// 추가되지 않는다 — 커밋 전 반영이 없다는 요구사항의 확인.
	@Test
	void sellRejectedByInsufficientQuantityLeavesRankingUntouched() throws Exception {
		User user = createUser("rank-reject");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();
		seedCryptoPrice(instrument, new BigDecimal("50000000"));

		// 보유 수량이 전혀 없는 상태에서 매도를 시도한다 — 잔고 검증에서 즉시 거부되고 어떤 것도 커밋되지 않는다.
		performOrder(accessToken, sellRequest(instrument.getId(), "0.01"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSUFFICIENT_QTY"));

		assertThat(scoreOf("CRYPTO", account.getId())).isNull();
	}

	// 시나리오 4: after-commit 랭킹 갱신이 Redis 장애(여기서는 RankingStore를 스텁해 예외를 던지도록 구성)로
	// 실패해도, 매도 체결 자체(주문·체결·계좌 갱신)는 정상 201로 성공해야 한다 — 리스너 레벨 try/catch가 실제로
	// 예외를 삼키는지를 통합 경로로 확인한다.
	@Test
	void rankingUpdateFailureDoesNotAffectSellExecution() throws Exception {
		User user = createUser("rank-redisdown");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();
		seedCryptoPrice(instrument, new BigDecimal("50000000"));

		performOrder(accessToken, buyRequest(instrument.getId(), "0.02"))
			.andExpect(status().isCreated());

		doThrow(new RuntimeException("redis down"))
			.when(rankingStore)
			.addScoreWithRetry(any(), any(), anyLong());

		seedCryptoPrice(instrument, new BigDecimal("80000000"));
		performOrder(accessToken, sellRequest(instrument.getId(), "0.01"))
			.andExpect(status().isCreated());

		// 랭킹 갱신이 실패했으므로 이 계좌는 ZSET에도 반영되지 않는다 — 하지만 매도 자체는 위에서 이미 201로 성공했다.
		assertThat(scoreOf("CRYPTO", account.getId())).isNull();
	}

	// 시나리오 5: 매도 체결 이력이 한 번도 없는 계좌(매수만 있음)는 랭킹 목록에 나타나지 않는다.
	@Test
	void accountWithNoSellHistoryIsExcludedFromRankingList() throws Exception {
		User user = createUser("rank-nosell");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();
		seedCryptoPrice(instrument, new BigDecimal("50000000"));

		performOrder(accessToken, buyRequest(instrument.getId(), "0.02"))
			.andExpect(status().isCreated());

		RankingListResponse rankings = getRankings(accessToken, "CRYPTO", 50);

		assertThat(rankings.content()).noneMatch(item -> item.nickname().equals(user.getNickname()));
	}

	// 시나리오 6: 실현손익이 같은 계좌 두 개는 공동 1위를, 그보다 낮은 계좌는 다음 순위(3위, 동점자 수만큼 건너뜀)를
	// 실제 Redis 데이터로 부여받는다.
	@Test
	void tiedRealizedPnlAccountsShareRankAndNextRankSkipsByTieCount() throws Exception {
		User userA = createUser("rank-tie-a");
		User userB = createUser("rank-tie-b");
		User userC = createUser("rank-tie-c");
		Account accountA = createAccount(userA);
		Account accountB = createAccount(userB);
		Account accountC = createAccount(userC);

		addRealizedPnlAndCommit(accountA.getId(), 500_000L);
		addRealizedPnlAndCommit(accountB.getId(), 500_000L);
		addRealizedPnlAndCommit(accountC.getId(), 300_000L);
		rankingService.refreshScore(accountA.getId());
		rankingService.refreshScore(accountB.getId());
		rankingService.refreshScore(accountC.getId());

		String accessToken = issueAccessToken(userA);
		RankingListResponse rankings = getRankings(accessToken, "CRYPTO", 50);

		assertThat(rankOf(rankings, userA.getNickname())).isEqualTo(1);
		assertThat(rankOf(rankings, userB.getNickname())).isEqualTo(1);
		assertThat(rankOf(rankings, userC.getNickname())).isEqualTo(3);
	}

	// 시나리오 7(PR #234 리뷰 권장 반영): RANK-002는 RANK-001의 topN(limit)과 달리 상위 노출 구간 밖에 있어도
	// 항상 정확한 보정 순위를 반환해야 한다 — 그 성질이 실제 Redis ZSET으로 검증된 적이 없었다(RankingServiceTest의
	// 단위 테스트는 countStrictlyGreater를 stub해 "+1" 산술만 확인할 뿐, 실제 limit 밖 순위 계산은 확인하지 못한다).
	// 기본 limit(10)보다 많은 계좌(11개)를 커밋해, 그중 순위가 11위(10위 밖)인 계좌로 GET /api/rankings/me를 호출한다.
	@Test
	void getMyRankingReturnsAccurateRankEvenOutsideDefaultListLimit() throws Exception {
		User lowestRankedUser = null;
		String lowestRankedAccessToken = null;
		for (int i = 1; i <= 11; i++) {
			User user = createUser("rank-outside-" + i);
			Account account = createAccount(user);
			long realizedPnl = (12 - i) * 100_000L; // i=1 → 1,100,000(1위) ... i=11 → 100,000(11위, limit 10 밖)
			addRealizedPnlAndCommit(account.getId(), realizedPnl);
			rankingService.refreshScore(account.getId());
			if (i == 11) {
				lowestRankedUser = user;
				lowestRankedAccessToken = issueAccessToken(user);
			}
		}

		MyRankingResponse response = getMyRanking(lowestRankedAccessToken, "CRYPTO");

		assertThat(response.rank()).isEqualTo(11);
		assertThat(response.realizedPnl()).isEqualTo(100_000L);
		assertThat(response.nickname()).isEqualTo(lowestRankedUser.getNickname());
	}

	// 시나리오 8(이슈 #288): 랭킹 목록 조회 자체가 Redis 연결 장애로 실패해도(RankingStore를 스텁해 재현) 500이
	// 아니라 200 + status(UNAVAILABLE)이어야 한다. 유실(REBUILDING)과 달리 재구성으로 해결되지 않는 장애다 —
	// 컨트롤러→서비스→스토어 실제 배선을 통해 GlobalExceptionHandler의 500 캐치올로 새지 않는지 확인한다.
	@Test
	void getRankingsReturnsUnavailableStatusInsteadOfFiveHundredWhenRankingStoreIsUnreachable() throws Exception {
		User user = createUser("rank-redis-outage");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		doThrow(new BusinessException(ErrorCode.RANKING_STORE_UNAVAILABLE, "redis down"))
			.when(rankingStore)
			.topN(any(), anyInt());

		mockMvc.perform(get("/api/rankings")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.market").value("CRYPTO"))
			.andExpect(jsonPath("$.status").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.content").isEmpty());
	}

	// 시나리오 9(이슈 #288): 내 랭킹 조회도 같은 장애에서 200 + status(UNAVAILABLE) + rank:null이어야 한다.
	// 계좌·닉네임은 DB 조회라 장애와 무관하게 정상 값으로 채워진다.
	@Test
	void getMyRankingReturnsUnavailableStatusInsteadOfFiveHundredWhenRankingStoreIsUnreachable() throws Exception {
		User user = createUser("rank-me-redis-outage");
		createAccount(user);
		String accessToken = issueAccessToken(user);
		doThrow(new BusinessException(ErrorCode.RANKING_STORE_UNAVAILABLE, "redis down"))
			.when(rankingStore)
			.score(any(), any());

		mockMvc.perform(get("/api/rankings/me")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.market").value("CRYPTO"))
			.andExpect(jsonPath("$.status").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.rank").doesNotExist())
			.andExpect(jsonPath("$.nickname").value(user.getNickname()));
	}

	private MyRankingResponse getMyRanking(String accessToken, String market) throws Exception {
		String body = mockMvc.perform(get("/api/rankings/me")
			.param("market", market)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readValue(body, MyRankingResponse.class);
	}

	private int rankOf(RankingListResponse rankings, String nickname) {
		return rankings.content().stream()
			.filter(item -> item.nickname().equals(nickname))
			.findFirst()
			.orElseThrow(() -> new AssertionError("랭킹 목록에서 닉네임을 찾을 수 없음: " + nickname))
			.rank();
	}

	// account.addRealizedPnl(...)을 실제로 커밋하고(직접 계좌 손익을 조작 — 매도 파이프라인을 거치지 않는
	// 시나리오 2·6에서만 사용) 갱신된 realized_pnl 값을 반환한다.
	private long addRealizedPnlAndCommit(Long accountId, long delta) {
		Account account = accountRepository.findById(accountId).orElseThrow();
		account.addRealizedPnl(delta);
		Account saved = accountRepository.saveAndFlush(account);
		return saved.getRealizedPnl();
	}

	private Double scoreOf(String market, Long accountId) {
		return redisTemplate.opsForZSet().score("ranking:" + market, String.valueOf(accountId));
	}

	private void cleanRankingKeys() {
		redisTemplate.delete("ranking:STOCK");
		redisTemplate.delete("ranking:CRYPTO");
	}

	private ResultActions performOrder(String accessToken, OrderCreateRequest request) throws Exception {
		return mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header(IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request)));
	}

	private RankingListResponse getRankings(String accessToken, String market, Integer limit) throws Exception {
		var requestBuilder = get("/api/rankings")
			.param("market", market)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
		if (limit != null) {
			requestBuilder = requestBuilder.param("limit", String.valueOf(limit));
		}
		String body = mockMvc.perform(requestBuilder)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readValue(body, RankingListResponse.class);
	}

	private OrderCreateRequest buyRequest(Long instrumentId, String quantity) {
		return new OrderCreateRequest(Market.CRYPTO, instrumentId, OrderSide.BUY, "MARKET", new BigDecimal(quantity));
	}

	private OrderCreateRequest sellRequest(Long instrumentId, String quantity) {
		return new OrderCreateRequest(Market.CRYPTO, instrumentId, OrderSide.SELL, "MARKET", new BigDecimal(quantity));
	}

	// 기존 시드 데이터(V7 마이그레이션, 코인 12종)에서 재사용한다 — 새 Instrument를 커밋하면 InstrumentRepositoryTest의
	// "정확히 28건" 단정 등을 깨뜨릴 위험이 있다(이 클래스는 @Transactional로 롤백시킬 수 없음, 클래스 상단 주석 참고).
	private Instrument firstCryptoInstrument() {
		List<Instrument> cryptos = instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO);
		assertThat(cryptos).isNotEmpty();
		return cryptos.get(0);
	}

	// 현재 clock 시각으로 틱을 저장한다 — PriceStore.isStale은 10초 임계값으로 판정하므로 항상 최신 시각을 써야 한다.
	// saveTick은 이전 저장값보다 이후 시각일 때만 갱신하므로, 같은 심볼에 여러 번 호출해도 항상 최신값으로 덮인다.
	private void seedCryptoPrice(Instrument instrument, BigDecimal price) {
		priceStore.saveTick(instrument.getSymbol(), price, LocalDateTime.now(clock));
	}

	private String issueAccessToken(User user) {
		return jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();
	}

	// createUser·createAccount가 이 클래스의 유일한 생성 지점이라, 여기서만 id를 모아두면 tearDown이 빠짐없이
	// 정리할 수 있다. 새 픽스처 헬퍼를 추가하면 여기에도 등록해야 한다.
	private User createUser(String scenario) {
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), LocalDateTime.now(clock)));
		createdUserIds.add(user.getId());
		return user;
	}

	private Account createAccount(User user) {
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, LocalDateTime.now(clock)));
		createdAccountIds.add(account.getId());
		return account;
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	// nickname 컬럼은 VARCHAR(50)(V2 마이그레이션)이라 UUID 전체(32자)를 붙이면 시나리오명이 길 때 초과한다
	// (OrderListIntegrationTest의 2026-07-30류 실수와 동일 함정). 8자로 줄여 여유를 둔다.
	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}
}
