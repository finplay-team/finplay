// ZSET을 실제로 비운 뒤 MySQL 원장에서 랭킹을 재구성하는 전체 경로를 Testcontainers MySQL+Redis로 검증한다.
package com.finplay.api.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.ranking.domain.RankingStatus;
import com.finplay.api.ranking.dto.response.MyRankingResponse;
import com.finplay.api.ranking.dto.response.RankingListResponse;
import com.finplay.api.ranking.service.RankingRebuildService;
import com.finplay.api.ranking.store.RankingStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

// 이 클래스는 @Transactional을 붙이지 않는다 — 재구성은 TradeService·AccountService가 각자 여는 트랜잭션에서
// DB를 다시 읽으므로, 픽스처가 실제로 커밋돼 있어야 재구성 대상으로 잡힌다. 롤백으로 격리할 수 없다는 뜻이라
// RankingIntegrationTest와 같은 방침을 따른다 — CRYPTO 시장만 사용해 STOCK 경로가 요구하는
// Instrument·StockReplaySession·StockCandle 신규 커밋을 피하고, 코인은 기존 시드 종목(V7)을 재사용한다.
//
// 공유 컨테이너 전제 두 가지를 픽스처 설계에 반영했다.
//  (1) 다른 통합 테스트(RankingIntegrationTest 등)가 CRYPTO 매도 체결을 커밋한 채 남긴다. 그래서 "이 시장에
//      매도 이력 계좌가 하나도 없다"를 전제하는 단정을 두지 않는다 — 목록 단정은 전부 포함/미포함 또는
//      재구성 전후 동일성으로 쓰고, 대상 0건(RENAME) 경계는 시장 전체가 아니라 RankingStore.replaceAll에
//      빈 목록을 직접 넘겨 재현한다(아래 해당 테스트 주석 참고).
//  (2) 재구성 크론은 테스트에서 꺼져 있지만(ranking-rebuild-schedule-disabled-for-tests.yml) 기동 훅은 살아
//      있어 컨텍스트 기동 시 rebuildAll()이 실제로 한 번 돈다. 각 테스트는 자기 픽스처를 커밋한 뒤 필요한
//      시점에 스스로 rebuild를 호출하므로 기동 시점의 1회 실행에 의존하지 않는다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RankingRebuildIntegrationTest {

	private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
	private static final com.finplay.api.account.domain.Market CRYPTO = com.finplay.api.account.domain.Market.CRYPTO;
	private static final com.finplay.api.account.domain.Market STOCK = com.finplay.api.account.domain.Market.STOCK;
	private static final BigDecimal BUY_PRICE = new BigDecimal("50000000");
	private static final String BUY_QUANTITY = "0.02";
	private static final String SELL_QUANTITY = "0.01";

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
	private ObjectMapper objectMapper;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private RankingRebuildService rankingRebuildService;

	@Autowired
	private RankingStore rankingStore;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// 이 클래스가 만든 계좌·회원 id. 비-@Transactional이라 매도 체결이 공유 MySQL에 실제로 커밋되므로,
	// 남겨두면 뒤에 도는 테스트가 "이 시장엔 매도 이력이 없다"를 단정할 수 없다. RankingIntegrationTest가
	// 같은 이유로 쓰는 정리 방식을 그대로 따른다(이슈 #279 작업 중 실측으로 확인한 오염이다).
	private final List<Long> createdAccountIds = new ArrayList<>();
	private final List<Long> createdUserIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	// @BeforeEach가 아니라 @AfterEach여야 한다 — 이 클래스의 시나리오 1은 "재구성 전후 목록이 같다"를 보므로,
	// 테스트 시작 전에 원장을 건드리면 before 스냅샷의 전제가 바뀐다. 정리는 각 테스트가 끝난 뒤에만 한다.
	//
	// **원장과 ZSET을 반드시 함께 지운다.** 계좌만 지우고 ZSET 멤버를 남기면 그 멤버는 DB에 없는 유령이 되는데,
	// 유령은 목록 응답에서는 필터링돼도 countStrictlyGreater(ZCOUNT)에는 그대로 세어져 순위를 부풀린다. 그러면
	// 시나리오 1에서 before는 유령이 낀 순위(공동 2위·5위), after는 재구성이 유령을 걷어낸 순위(공동 1위·3위)가
	// 되어 "전후 동일"이 깨진다 — 실제로 ZREM을 빼고 돌려 이 실패를 확인했다. 이 클래스가 성립하는 전제는
	// "ZSET 상태 ≈ 원장을 재구성한 결과"이며, 정리도 그 전제를 유지하는 방향이어야 한다.
	@AfterEach
	void tearDown() {
		if (!createdAccountIds.isEmpty()) {
			String[] members = createdAccountIds.stream().map(String::valueOf).toArray(String[]::new);
			redisTemplate.opsForZSet().remove(rankingKey(CRYPTO), (Object[])members);
			redisTemplate.opsForZSet().remove(rankingKey(STOCK), (Object[])members);

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
		if (!createdUserIds.isEmpty()) {
			String userIdIn = createdUserIds.stream().map(String::valueOf).collect(Collectors.joining(","));
			jdbcTemplate.update("delete from accounts where user_id in (" + userIdIn + ")");
			jdbcTemplate.update("delete from users where id in (" + userIdIn + ")");
			createdUserIds.clear();
		}
	}

	// 시나리오 1: ZSET을 통째로 DEL한 뒤 재구성하면 유실 전과 같은 순위·금액이 그대로 복원된다.
	// 앞부분에서 "매도 체결이 실시간(after-commit)으로 쌓아 올린 score"와 "재구성이 원장에서 다시 계산한 score"가
	// 같다는 것을 먼저 확인한다 — 이것이 복원의 정확성을 보장하는 핵심이고, 뒤의 목록 동일성 단정만으로는
	// (두 스냅샷이 모두 재구성 결과이므로) 이 성질이 검증되지 않는다.
	@Test
	void rebuildRestoresIdenticalRanksAndAmountsAfterZsetIsLost() throws Exception {
		SoldAccount tieA = sellForNaturalPnl("rbld-tie-a", new BigDecimal("80000000"));
		SoldAccount tieB = sellForNaturalPnl("rbld-tie-b", new BigDecimal("80000000"));
		SoldAccount lower = sellForNaturalPnl("rbld-lower", new BigDecimal("60000000"));

		// 매도 체결이 실시간으로 넣어 둔 score(유실 전 실제 값)를 먼저 붙잡는다.
		Long liveScoreA = rankingStore.score(CRYPTO, tieA.accountId());
		Long liveScoreB = rankingStore.score(CRYPTO, tieB.accountId());
		Long liveScoreLower = rankingStore.score(CRYPTO, lower.accountId());
		assertThat(liveScoreA).isNotNull();
		assertThat(liveScoreA).isEqualTo(liveScoreB); // 같은 매수·매도 조건이라 동점이다.
		assertThat(liveScoreLower).isLessThan(liveScoreA);

		RankingListResponse before = getRankings(tieA.accessToken());
		assertThat(before.status()).isEqualTo(RankingStatus.READY);
		assertThat(rankOf(before, tieA.nickname())).isEqualTo(rankOf(before, tieB.nickname()));
		assertThat(rankOf(before, lower.nickname())).isGreaterThan(rankOf(before, tieA.nickname()));

		deleteRankingKey(CRYPTO);
		assertThat(rankingStore.score(CRYPTO, tieA.accountId())).isNull();

		rankingRebuildService.rebuild(CRYPTO);

		// 재구성 결과가 유실 전 실시간 score와 정확히 같다.
		assertThat(rankingStore.score(CRYPTO, tieA.accountId())).isEqualTo(liveScoreA);
		assertThat(rankingStore.score(CRYPTO, tieB.accountId())).isEqualTo(liveScoreB);
		assertThat(rankingStore.score(CRYPTO, lower.accountId())).isEqualTo(liveScoreLower);

		// 그래서 목록 응답(순위·닉네임·금액)도 유실 전과 완전히 동일하다 — 공동 순위와 건너뛴 다음 순위까지 포함해서다.
		RankingListResponse after = getRankings(tieA.accessToken());
		assertThat(after.status()).isEqualTo(RankingStatus.READY);
		assertThat(after.content()).isEqualTo(before.content());
	}

	// 시나리오 2: score의 정본은 accounts.realized_pnl 컬럼이다. ZSET에 남아 있던 값이 아니라 DB 컬럼을 읽는지
	// 확인하기 위해, 매도 체결 이후 realized_pnl만 다른 값으로 커밋해 두 값을 일부러 어긋내고 재구성한다.
	@Test
	void rebuiltScoreComesFromAccountsRealizedPnlColumn() throws Exception {
		SoldAccount sold = sellForNaturalPnl("rbld-column", new BigDecimal("80000000"));
		Long staleScore = rankingStore.score(CRYPTO, sold.accountId());
		assertThat(staleScore).isNotNull();

		long divergedRealizedPnl = 777_777L;
		setRealizedPnlAndCommit(sold.accountId(), divergedRealizedPnl);
		assertThat(divergedRealizedPnl).isNotEqualTo(staleScore);
		// 아직 재구성 전이라 ZSET은 여전히 옛 값을 들고 있다.
		assertThat(rankingStore.score(CRYPTO, sold.accountId())).isEqualTo(staleScore);

		rankingRebuildService.rebuild(CRYPTO);

		assertThat(rankingStore.score(CRYPTO, sold.accountId())).isEqualTo(divergedRealizedPnl);
		assertThat(rankingStore.score(CRYPTO, sold.accountId())).isEqualTo(realizedPnlColumnOf(sold.accountId()));
	}

	// 시나리오 3: 대상 판정 기준은 매도 체결 이력이지 realized_pnl 값이 아니다. 매도했는데 손익이 정확히 0인
	// 계좌가 빠지면 재구성 결과가 유실 전과 달라진다 — 이 정책의 존재 이유를 실데이터로 못박는다.
	@Test
	void rebuildIncludesSoldAccountWhoseRealizedPnlIsExactlyZero() throws Exception {
		SoldAccount sold = sellForNaturalPnl("rbld-zero", new BigDecimal("80000000"));
		setRealizedPnlAndCommit(sold.accountId(), 0L);
		assertThat(realizedPnlColumnOf(sold.accountId())).isZero();
		deleteRankingKey(CRYPTO);

		rankingRebuildService.rebuild(CRYPTO);

		assertThat(rankingStore.score(CRYPTO, sold.accountId())).isZero();
	}

	// 시나리오 4: 매수만 하고 매도한 적이 없는 계좌는 재구성 대상이 아니다(ZSET에 member 자체가 생기지 않는다).
	@Test
	void rebuildExcludesAccountWithoutAnySellHistory() throws Exception {
		User user = createUser("rbld-nosell");
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();
		seedCryptoPrice(instrument, BUY_PRICE);
		performOrder(accessToken, buyRequest(instrument.getId())).andExpect(status().isCreated());

		deleteRankingKey(CRYPTO);
		rankingRebuildService.rebuild(CRYPTO);

		assertThat(rankingStore.score(CRYPTO, account.getId())).isNull();
	}

	// 시나리오 5: 대상 0건일 때의 RENAME 경계. 임시 키에 ZADD가 한 번도 실행되지 않으면 그 키가 존재하지 않고,
	// 없는 키에 대한 RENAME은 실 Redis에서 "ERR no such key"다 — mock으로는 재현되지 않고 여기서만 드러난다.
	// replaceAll이 그 경계에서 RENAME 대신 본 키 DEL을 선택하지 않았다면, 예외가 catch로 삼켜지면서 본 키의
	// 옛 값이 그대로 남는다. 그래서 "예외가 안 났다"가 아니라 "옛 값이 실제로 사라졌다"로 단정한다.
	//
	// 시장 전체를 비우는 방식은 쓰지 않는다 — 공유 컨테이너에는 다른 테스트가 커밋한 매도 체결이 남아 있어
	// 어느 시장도 "대상 0건"을 보장할 수 없다(클래스 상단 주석 (1)). 대신 대상 0건 상황과 정확히 같은 입력인
	// 빈 목록을 RankingStore에 직접 넘긴다.
	@Test
	void replaceAllDeletesLiveKeyInsteadOfFailingRenameWhenNoAccountsQualify() {
		String liveKey = rankingKey(STOCK);
		String rebuildKey = liveKey + ":rebuild";
		redisTemplate.delete(rebuildKey);
		redisTemplate.opsForZSet().add(liveKey, "999999", 12_345d);
		assertThat(redisTemplate.hasKey(liveKey)).isTrue();

		rankingStore.replaceAll(STOCK, List.of());

		assertThat(redisTemplate.hasKey(liveKey)).isFalse();
		assertThat(redisTemplate.hasKey(rebuildKey)).isFalse();
	}

	// 시나리오 6: 재구성 전(유실 상태)에도 두 엔드포인트는 오류가 아니라 200 + status: REBUILDING을 반환하고,
	// 재구성 후에는 READY로 돌아온다. 내 랭킹은 rank가 null인데 그 null의 의미가 "매도 이력 없음"이 아니다.
	@Test
	void bothEndpointsReturnOkWithRebuildingStatusBeforeRebuildAndReadyAfter() throws Exception {
		SoldAccount sold = sellForNaturalPnl("rbld-status", new BigDecimal("80000000"));
		deleteRankingKey(CRYPTO);

		mockMvc.perform(get("/api/rankings")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + sold.accessToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REBUILDING"))
			.andExpect(jsonPath("$.content").isEmpty());

		MyRankingResponse lost = getMyRanking(sold.accessToken());
		assertThat(lost.status()).isEqualTo(RankingStatus.REBUILDING);
		assertThat(lost.rank()).isNull();
		assertThat(lost.nickname()).isEqualTo(sold.nickname());

		rankingRebuildService.rebuild(CRYPTO);

		MyRankingResponse restored = getMyRanking(sold.accessToken());
		assertThat(restored.status()).isEqualTo(RankingStatus.READY);
		assertThat(restored.rank()).isNotNull();
		assertThat(restored.realizedPnl()).isEqualTo(realizedPnlColumnOf(sold.accountId()));
	}

	// 시나리오 7(이슈 #279 완료 조건): 재구성 전후로 주문·체결·계좌(잔액·실현손익)·보유·lot 원장이 변하지 않는다.
	// 행 수만 비교하면 UPDATE가 잡히지 않으므로(agent-mistakes.md 2026-08-04) 값까지 비교한다. 범위는 이 테스트가
	// 만든 계좌로 한정한다 — 공유 컨테이너의 전역 스냅샷은 다른 테스트의 커밋에 흔들린다.
	@Test
	void ledgerIsUnchangedAcrossRebuild() throws Exception {
		SoldAccount sold = sellForNaturalPnl("rbld-ledger", new BigDecimal("80000000"));
		LedgerSnapshot before = snapshotLedgerOf(sold.accountId());
		assertThat(before.orders()).isNotEmpty();
		assertThat(before.trades()).hasSize(2); // 매수 1 + 매도 1
		assertThat(before.holdings()).isNotEmpty();
		assertThat(before.holdingLots()).isNotEmpty();

		deleteRankingKey(CRYPTO);
		rankingRebuildService.rebuild(CRYPTO);
		// 재구성이 ZSET을 실제로 채웠는지 먼저 확인한다 — 아무 일도 하지 않았다면 "불변"은 증거가 되지 못한다.
		assertThat(rankingStore.score(CRYPTO, sold.accountId())).isNotNull();

		// 이 단정이 실제로 무엇을 잡는지는 아래 ledgerSnapshotDetectsAChangeInEveryTrackedTable이 상시로
		// 증명한다 — 손으로 한 번 변형해 본 기록이 아니라 테스트로 남겨 둔다.
		assertThat(snapshotLedgerOf(sold.accountId())).isEqualTo(before);
	}

	// 위 불변 단정의 감시 범위를 상시 검증한다. LedgerSnapshot이 5개 테이블을 "값으로" 비교한다는 것은
	// 스냅샷이 그 컬럼들을 실제로 읽고 있을 때만 참이다 — SELECT 목록에서 컬럼이 빠지거나 테이블이 통째로
	// 빠지면 ledgerIsUnchangedAcrossRebuild는 아무것도 지키지 않으면서 초록으로 남는다(통합 테스트가
	// 공허해지는 가장 흔한 경로다). 그래서 테이블마다 값을 한 칸 흔들어 스냅샷이 그것을 잡아내는지 확인하고
	// 곧바로 되돌린다.
	//
	// 이 계좌의 주문은 전부 MARKET이라 limit_price가 NULL이다 — NULL에 +1을 해도 NULL이라 변화가 없으므로
	// 그 컬럼만 리터럴로 채웠다가 NULL로 되돌린다.
	@Test
	void ledgerSnapshotDetectsAChangeInEveryTrackedTable() throws Exception {
		SoldAccount sold = sellForNaturalPnl("rbld-snapshot", new BigDecimal("80000000"));
		Long accountId = sold.accountId();
		LedgerSnapshot baseline = snapshotLedgerOf(accountId);

		assertSnapshotDetects(accountId, baseline,
			"UPDATE accounts SET cash_balance = cash_balance + 1 WHERE id = ?",
			"UPDATE accounts SET cash_balance = cash_balance - 1 WHERE id = ?");
		assertSnapshotDetects(accountId, baseline,
			"UPDATE accounts SET realized_pnl = realized_pnl + 1 WHERE id = ?",
			"UPDATE accounts SET realized_pnl = realized_pnl - 1 WHERE id = ?");
		assertSnapshotDetects(accountId, baseline,
			"UPDATE accounts SET reserved_cash = reserved_cash + 1 WHERE id = ?",
			"UPDATE accounts SET reserved_cash = reserved_cash - 1 WHERE id = ?");
		assertSnapshotDetects(accountId, baseline,
			"UPDATE orders SET quantity = quantity + 1 WHERE account_id = ?",
			"UPDATE orders SET quantity = quantity - 1 WHERE account_id = ?");
		assertSnapshotDetects(accountId, baseline,
			"UPDATE orders SET limit_price = 1 WHERE account_id = ?",
			"UPDATE orders SET limit_price = NULL WHERE account_id = ?");
		assertSnapshotDetects(accountId, baseline,
			"UPDATE trades SET fee = fee + 1 WHERE account_id = ?",
			"UPDATE trades SET fee = fee - 1 WHERE account_id = ?");
		assertSnapshotDetects(accountId, baseline,
			"UPDATE holdings SET quantity = quantity + 1 WHERE account_id = ?",
			"UPDATE holdings SET quantity = quantity - 1 WHERE account_id = ?");
		assertSnapshotDetects(accountId, baseline,
			"UPDATE holdings SET reserved_quantity = reserved_quantity + 1 WHERE account_id = ?",
			"UPDATE holdings SET reserved_quantity = reserved_quantity - 1 WHERE account_id = ?");
		assertSnapshotDetects(accountId, baseline,
			"UPDATE holding_lots SET remaining_quantity = remaining_quantity + 1"
				+ " WHERE holding_id IN (SELECT id FROM holdings WHERE account_id = ?)",
			"UPDATE holding_lots SET remaining_quantity = remaining_quantity - 1"
				+ " WHERE holding_id IN (SELECT id FROM holdings WHERE account_id = ?)");
	}

	// 변형 → "스냅샷이 달라졌는가" 단정 → 원복 → "정확히 원래대로 돌아왔는가" 단정. 원복은 finally에 둬서
	// 중간 단정이 실패해도 공유 MySQL에 흔든 값이 남지 않게 한다.
	private void assertSnapshotDetects(Long accountId, LedgerSnapshot baseline, String mutate, String restore) {
		int mutated = jdbcTemplate.update(mutate, accountId);
		try {
			assertThat(mutated).as("변형이 한 행도 바꾸지 못했다 — 픽스처가 비어 있다: %s", mutate).isPositive();
			assertThat(snapshotLedgerOf(accountId))
				.as("스냅샷이 이 변경을 잡아내지 못한다 — 불변 단정의 감시 범위 밖이다: %s", mutate)
				.isNotEqualTo(baseline);
		} finally {
			jdbcTemplate.update(restore, accountId);
		}
		assertThat(snapshotLedgerOf(accountId)).as("원복되지 않았다: %s", restore).isEqualTo(baseline);
	}

	// --- 픽스처 헬퍼 ---

	// 매수 후 지정한 가격으로 절반을 매도해 실제 SELL 체결 행과 자연스러운 realized_pnl을 만든다.
	// 이 매도의 after-commit 리스너가 ZSET에도 score를 넣으므로, 호출 직후의 ZSET 상태가 "유실 전 실제 값"이다.
	private SoldAccount sellForNaturalPnl(String scenario, BigDecimal sellPrice) throws Exception {
		User user = createUser(scenario);
		Account account = createAccount(user);
		String accessToken = issueAccessToken(user);
		Instrument instrument = firstCryptoInstrument();

		seedCryptoPrice(instrument, BUY_PRICE);
		performOrder(accessToken, buyRequest(instrument.getId())).andExpect(status().isCreated());

		seedCryptoPrice(instrument, sellPrice);
		performOrder(accessToken, sellRequest(instrument.getId())).andExpect(status().isCreated());

		return new SoldAccount(account.getId(), user.getNickname(), accessToken);
	}

	// accounts.realized_pnl을 원하는 값으로 맞춰 커밋한다(체결 파이프라인을 거치지 않는 직접 조정).
	// 이 조정은 랭킹 이벤트를 발행하지 않으므로 ZSET은 옛 값을 그대로 들고 있다 — 재구성이 DB를 다시 읽는지
	// 확인하는 데 필요한 성질이다.
	private void setRealizedPnlAndCommit(Long accountId, long target) {
		Account account = accountRepository.findById(accountId).orElseThrow();
		account.addRealizedPnl(target - account.getRealizedPnl());
		accountRepository.saveAndFlush(account);
	}

	private long realizedPnlColumnOf(Long accountId) {
		return jdbcTemplate.queryForObject("SELECT realized_pnl FROM accounts WHERE id = ?", Long.class, accountId);
	}

	// 이 테스트 메서드는 트랜잭션 밖에서 돌고 픽스처는 전부 saveAndFlush로 커밋돼 있으므로, 보류된 쓰기가
	// 스냅샷에서 빠지는 문제(agent-mistakes.md 2026-08-04)가 발생하지 않는다.
	// V22(지정가 예약 원장)가 더한 세 컬럼(orders.limit_price·accounts.reserved_cash·holdings.reserved_quantity)도
	// 함께 읽는다. 재구성이 건드릴 이유가 없는 것은 다른 컬럼과 같지만, "원장 불변"을 단정하면서 원장의 일부를
	// 아예 보지 않으면 그 범위의 변경은 영원히 통과한다 — 잔액 계열(cash_balance/reserved_cash)은 특히 그렇다.
	private LedgerSnapshot snapshotLedgerOf(Long accountId) {
		return new LedgerSnapshot(
			jdbcTemplate.queryForList(
				"SELECT id, user_id, account_id, instrument_id, side, order_type, status, quantity, limit_price"
					+ " FROM orders WHERE account_id = ? ORDER BY id",
				accountId),
			jdbcTemplate.queryForList(
				"SELECT id, order_id, account_id, instrument_id, side, price, quantity, amount, fee, realized_pnl"
					+ " FROM trades WHERE account_id = ? ORDER BY id",
				accountId),
			jdbcTemplate.queryForList(
				"SELECT id, cash_balance, reserved_cash, realized_pnl FROM accounts WHERE id = ?", accountId),
			jdbcTemplate.queryForList(
				"SELECT id, instrument_id, quantity, reserved_quantity, average_price, is_active"
					+ " FROM holdings WHERE account_id = ? ORDER BY id",
				accountId),
			jdbcTemplate.queryForList(
				"SELECT l.id, l.holding_id, l.original_quantity, l.remaining_quantity, l.unit_cost, l.buy_fee"
					+ " FROM holding_lots l JOIN holdings h ON h.id = l.holding_id"
					+ " WHERE h.account_id = ? ORDER BY l.id",
				accountId));
	}

	private record LedgerSnapshot(
		List<Map<String, Object>> orders,
		List<Map<String, Object>> trades,
		List<Map<String, Object>> accounts,
		List<Map<String, Object>> holdings,
		List<Map<String, Object>> holdingLots) {
	}

	private record SoldAccount(Long accountId, String nickname, String accessToken) {
	}

	// --- 조회·유틸 헬퍼 ---

	private RankingListResponse getRankings(String accessToken) throws Exception {
		String body = mockMvc.perform(get("/api/rankings")
			.param("market", "CRYPTO")
			.param("limit", "50")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readValue(body, RankingListResponse.class);
	}

	private MyRankingResponse getMyRanking(String accessToken) throws Exception {
		String body = mockMvc.perform(get("/api/rankings/me")
			.param("market", "CRYPTO")
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

	private void deleteRankingKey(com.finplay.api.account.domain.Market market) {
		redisTemplate.delete(rankingKey(market));
	}

	private String rankingKey(com.finplay.api.account.domain.Market market) {
		return "ranking:" + market.name();
	}

	private ResultActions performOrder(String accessToken, OrderCreateRequest request) throws Exception {
		return mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header(IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(request)));
	}

	private OrderCreateRequest buyRequest(Long instrumentId) {
		return new OrderCreateRequest(Market.CRYPTO, instrumentId, OrderSide.BUY, "MARKET",
			new BigDecimal(BUY_QUANTITY));
	}

	private OrderCreateRequest sellRequest(Long instrumentId) {
		return new OrderCreateRequest(Market.CRYPTO, instrumentId, OrderSide.SELL, "MARKET",
			new BigDecimal(SELL_QUANTITY));
	}

	// 기존 시드 데이터(V7 마이그레이션, 코인 12종)를 재사용한다 — 새 Instrument를 커밋하면 InstrumentRepositoryTest의
	// 절대 개수 단정을 깨뜨린다(이 클래스는 @Transactional로 롤백시킬 수 없다).
	private Instrument firstCryptoInstrument() {
		List<Instrument> cryptos = instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO);
		assertThat(cryptos).isNotEmpty();
		return cryptos.get(0);
	}

	private void seedCryptoPrice(Instrument instrument, BigDecimal price) {
		priceStore.saveTick(instrument.getSymbol(), price, LocalDateTime.now(clock));
	}

	private String issueAccessToken(User user) {
		return jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();
	}

	// createUser·createAccount가 이 클래스의 유일한 픽스처 생성 지점이라, 여기서만 id를 모으면 tearDown이
	// 빠짐없이 정리한다. 새 픽스처 헬퍼를 추가하면 여기에도 등록해야 한다.
	private User createUser(String scenario) {
		User user = userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), LocalDateTime.now(clock)));
		createdUserIds.add(user.getId());
		return user;
	}

	private Account createAccount(User user) {
		Account account = accountRepository.saveAndFlush(Account.create(user, CRYPTO, LocalDateTime.now(clock)));
		createdAccountIds.add(account.getId());
		return account;
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	// nickname은 VARCHAR(50)이라 UUID 전체를 붙이면 시나리오명이 길 때 초과한다 — 8자로 줄여 여유를 둔다.
	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}
}
