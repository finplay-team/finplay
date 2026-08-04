// 고정 Clock + 대역 생성기 + 실 MySQL로 서술 재생성의 게이트 통과 1회·누적 상한·DB 전이 값을 검증한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.feedback.config.FeedbackLlmProperties;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.PostSellFeedbackStatus;
import com.finplay.api.feedback.dto.response.Counterfactuals;
import com.finplay.api.feedback.dto.response.CounterfactualScenario;
import com.finplay.api.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.feedback.dto.response.NewsItem;
import com.finplay.api.feedback.dto.response.PeerComparison;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.feedback.dto.response.PostSellFlow;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

// 이슈 #208 5번 항목의 완료 조건 셋이 목표다 — 게이트 통과 후 1회, 누적 상한 초과 없음, 카드 0건인 매도도
// 재생성이 일어남.
//
// **PostSellFeedbackReader를 mock으로 대체한다.** 재생성 게이트는 peerComparison.status가 NOT_YET이 아니어야
// 열리는데 3번 항목이 그 값을 상수 NOT_YET으로 두어 이 이슈 범위에서는 구조적으로 열리지 않는다(설계다 —
// 7번이 실제 판정을 붙이면 한 줄도 안 고치고 열린다). 게이트 조건을 느슨하게 고치는 대신 reader가 확정 상태를
// 돌려주게 만든다.
//
// **DB 값을 확인하는 것이 이 파일의 핵심이다.** writer의 두 전이 메서드가 행을 자기 트랜잭션에서 다시 읽는데,
// 이것을 "호출부가 넘긴 detached 엔티티에 전이 메서드 호출"로 바꾸면 예외도 로그도 없이 아무 일도 일어나지
// 않는다 — 메모리 객체만 보는 단정은 그때도 초록이다.
//
// 실제 LLM을 부르지 않는다 (ADR-0011·PRD C-005).
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class,
	PostSellFeedbackRegenerationIntegrationTest.RegenerationTestConfig.class})
class PostSellFeedbackRegenerationIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate TRADE_SERVICE_DATE = LocalDate.of(2026, 8, 4);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);
	private static final LocalDateTime VIEW_AT = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(16, 0));

	// 후검증 5줄을 통과하는 관찰형 문장 두 개 — 최초 생성과 재생성을 구분한다.
	private static final String FIRST_NARRATIVE = "09시 30분에 70,000원에 매수한 뒤 14시 40분에 68,500원에 매도했습니다.";
	private static final String REGENERATED_NARRATIVE = "마감 종가는 69,200원으로 매도가보다 1.02% 높습니다.";

	@Autowired
	private PostSellFeedbackService postSellFeedbackService;

	@Autowired
	private FakeNarrativeGenerator fakeNarrativeGenerator;

	@Autowired
	private FeedbackLlmProperties feedbackLlmProperties;

	// 게이트를 열려면 peerComparison.status가 확정 상태여야 한다 — 3번 항목이 상수 NOT_YET으로 둔 자리다.
	@MockitoBean
	private PostSellFeedbackReader postSellFeedbackReader;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	private User owner;
	private Trade sellTrade;

	@BeforeEach
	void setUp() {
		fakeNarrativeGenerator.reset();

		owner = userRepository.saveAndFlush(User.create("post-sell-regen@finplay.com", "hash", "regen208", VIEW_AT));
		Account account = accountRepository.saveAndFlush(
			Account.create(owner, com.finplay.api.account.domain.Market.STOCK, VIEW_AT));
		// V7 시드 심볼과 겹치지 않는 테스트 전용 심볼 — UNIQUE(symbol) 충돌 방지.
		Instrument stock = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST208R", "테스트종목208R", BigDecimal.valueOf(100), 10_000L, true,
				VIEW_AT));
		LocalDateTime resolvedAt = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(8, 40));
		StockReplaySession session = stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(TRADE_SERVICE_DATE, ORIGIN_TRADE_DATE, resolvedAt, resolvedAt));

		LocalDateTime executedAt = LocalDateTime.of(TRADE_SERVICE_DATE, SELL_TIME);
		Order order = orderRepository.saveAndFlush(Order.create(
			owner, account, stock, OrderSide.SELL, OrderType.MARKET, new BigDecimal("10"),
			"idem-" + System.nanoTime(), "a".repeat(64), executedAt));
		sellTrade = tradeRepository.saveAndFlush(Trade.of(
			order, account, stock, session, OrderSide.SELL, new BigDecimal("68500"), new BigDecimal("10"),
			685_000L, 102L, -15_207L, executedAt, executedAt));
	}

	// --- 게이트 통과 후 1회 (완료 조건 8번) ---

	// 카드 0건(NO_EVENT) 픽스처가 이 항목의 핵심이다 — 게이트를 "확정 집계 행 존재"로 판정한 구현은 이 경우에
	// 영원히 재생성하지 않고, 카드 0건은 운영에서 가장 흔한 경우다.
	@Test
	@DisplayName("카드 0건(NO_EVENT)이어도 게이트 통과 후 첫 조회에서 재생성되고 두 번째 조회에서는 재생성되지 않는다")
	void regeneratesOnceAfterTheGateOpensEvenWithoutAnyCard() {
		givenFacts(PostSellFeedbackStatus.NO_EVENT, false);
		fakeNarrativeGenerator.enqueue(FIRST_NARRATIVE).enqueue(REGENERATED_NARRATIVE);

		// 1회차 — 최초 생성. 게이트는 열려 있지만 기존 행이 없으므로 생성 경로다.
		PostSellFeedbackResponse created = getPostSellFeedback();
		assertThat(created.narrative()).isEqualTo(FIRST_NARRATIVE);
		assertThat(feedbackRow()).containsEntry("narrative_finalized", false);

		// 2회차 — 게이트가 열려 있고 확정 전이므로 재생성한다.
		PostSellFeedbackResponse regenerated = getPostSellFeedback();

		assertThat(regenerated.narrative()).isEqualTo(REGENERATED_NARRATIVE);
		assertThat(regenerated.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(regenerated.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		// DB 값으로 전이를 확인한다 — detached 엔티티에 전이 메서드를 부른 구현은 여기서만 빨개진다.
		assertThat(feedbackRow())
			.containsEntry("narrative", REGENERATED_NARRATIVE)
			.containsEntry("narrative_finalized", true)
			.containsEntry("regeneration_attempts", 1);
		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(2);

		// 3회차 — 확정됐으므로 재생성하지 않는다.
		PostSellFeedbackResponse reused = getPostSellFeedback();

		assertThat(reused.narrative()).isEqualTo(REGENERATED_NARRATIVE);
		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(2);
		assertThat(feedbackRow()).containsEntry("regeneration_attempts", 1);
	}

	@Test
	@DisplayName("집단 비교가 INSUFFICIENT_SAMPLE이어도 확정으로 쳐서 재생성한다")
	void regeneratesWhenThePeerSampleIsInsufficient() {
		givenFacts(PostSellFeedbackStatus.INSUFFICIENT_SAMPLE, true);
		fakeNarrativeGenerator.enqueue(FIRST_NARRATIVE).enqueue(REGENERATED_NARRATIVE);

		getPostSellFeedback();
		PostSellFeedbackResponse regenerated = getPostSellFeedback();

		assertThat(regenerated.narrative()).isEqualTo(REGENERATED_NARRATIVE);
		assertThat(feedbackRow()).containsEntry("narrative_finalized", true);
	}

	// 게이트가 열린 뒤의 재생성 프롬프트에는 매도 후 흐름 줄이 붙는다 — 매핑을 따로 만들면 그 줄이 빠진
	// 프롬프트로 재생성해 게이트가 무의미해진다.
	@Test
	@DisplayName("재생성 프롬프트에 매도 후 흐름 줄이 실제로 들어간다")
	void putsThePostSellFlowLineIntoTheRegenerationPrompt() {
		givenFacts(PostSellFeedbackStatus.NO_EVENT, true);
		fakeNarrativeGenerator.enqueue(FIRST_NARRATIVE).enqueue(REGENERATED_NARRATIVE);

		getPostSellFeedback();
		getPostSellFeedback();

		assertThat(fakeNarrativeGenerator.userPrompts()).hasSize(2);
		assertThat(fakeNarrativeGenerator.userPrompts().get(1))
			.contains("매도 후 흐름")
			.contains("69,200원");
	}

	// --- 누적 상한 (완료 조건 9번) ---

	// 상한 + 1회를 재현한다 — 상한 이하만 재현하면 날짜 리셋 버그를 잡지 못한다. 실패는 템플릿 폴백이며
	// 대역 생성기에 응답을 넣지 않으면 그 경로가 된다.
	@Test
	@DisplayName("재생성 실패가 누적 상한을 넘지 않고 기존 서술과 narrative_finalized=false가 유지된다")
	void neverExceedsTheCumulativeRetryLimit() {
		int limit = feedbackLlmProperties.maxNarrativeRetry();
		givenFacts(PostSellFeedbackStatus.NO_EVENT, true);
		// 최초 생성 1회만 성공시키고 이후 재생성은 전부 실패(템플릿 폴백)로 만든다.
		fakeNarrativeGenerator.enqueue(FIRST_NARRATIVE);

		getPostSellFeedback();
		assertThat(feedbackRow()).containsEntry("regeneration_attempts", 0);

		// 상한 횟수만큼 실패한다 — 매번 호출이 일어나고 횟수가 오른다.
		for (int attempt = 1; attempt <= limit; attempt++) {
			PostSellFeedbackResponse response = getPostSellFeedback();

			assertThat(response.narrative()).as("실패해도 기존 서술이 유지된다").isEqualTo(FIRST_NARRATIVE);
			assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.LLM);
			assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
			assertThat(feedbackRow())
				.containsEntry("regeneration_attempts", attempt)
				.containsEntry("narrative_finalized", false)
				.containsEntry("narrative", FIRST_NARRATIVE);
			assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(attempt + 1);
		}

		// 상한 + 1회차 — 여기서 LLM을 부르면 실패하는 체결 하나가 조회마다 호출을 내는데 응답은 정상 200이라
		// 아무 신호도 남지 않는다.
		int callsAtLimit = fakeNarrativeGenerator.callCount();
		PostSellFeedbackResponse afterLimit = getPostSellFeedback();

		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(callsAtLimit);
		assertThat(afterLimit.narrative()).isEqualTo(FIRST_NARRATIVE);
		assertThat(afterLimit.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(feedbackRow())
			.containsEntry("regeneration_attempts", limit)
			.containsEntry("narrative_finalized", false);
	}

	// --- 게이트가 닫힌 상태 ---

	@Test
	@DisplayName("집단 비교가 NOT_YET이면 재생성하지 않고 최초 서술을 그대로 재사용한다")
	void neverRegeneratesWhilePeerComparisonIsNotYet() {
		givenFacts(PostSellFeedbackStatus.NOT_YET, true);
		fakeNarrativeGenerator.enqueue(FIRST_NARRATIVE).enqueue(REGENERATED_NARRATIVE);

		getPostSellFeedback();
		PostSellFeedbackResponse second = getPostSellFeedback();

		assertThat(second.narrative()).isEqualTo(FIRST_NARRATIVE);
		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(1);
		assertThat(feedbackRow())
			.containsEntry("regeneration_attempts", 0)
			.containsEntry("narrative_finalized", false);
	}

	// --- 픽스처 ---

	private PostSellFeedbackResponse getPostSellFeedback() {
		return postSellFeedbackService.getPostSellFeedback(owner.getId(), sellTrade.getId());
	}

	/** {@code flush()}가 전제다 — 보류된 UPDATE는 raw JDBC에 보이지 않아 전이가 없는 구현도 초록이 된다. */
	private Map<String, Object> feedbackRow() {
		entityManager.flush();
		return jdbcTemplate.queryForMap(
			"SELECT narrative, narrative_source, narrative_finalized, regeneration_attempts, generated_at "
				+ "FROM trade_feedbacks WHERE trade_id = ?",
			sellTrade.getId());
	}

	private void givenFacts(PostSellFeedbackStatus peerStatus, boolean withCard) {
		org.mockito.Mockito.when(postSellFeedbackReader.read(owner.getId(), sellTrade.getId()))
			.thenAnswer(call -> facts(peerStatus, withCard));
	}

	/** 장 마감 게이트가 열린 뒤의 조립 결과 — {@code postSellFlow}가 {@code READY}다. */
	private PostSellFeedbackResponse facts(PostSellFeedbackStatus peerStatus, boolean withCard) {
		return new PostSellFeedbackResponse(
			sellTrade.getId(),
			sellTrade.getInstrument().getId(),
			"TEST208R",
			"테스트종목208R",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 30)),
			LocalDateTime.of(ORIGIN_TRADE_DATE, SELL_TIME),
			new BigDecimal("70000.00000000"),
			new BigDecimal("68500"),
			new BigDecimal("10"),
			102L,
			-15_207L,
			new BigDecimal("-0.0217"),
			310,
			true,
			new BigDecimal("70800"),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 5)),
			new BigDecimal("68100"),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 20)),
			new BigDecimal("-0.0325"),
			new BigDecimal("0.0059"),
			withCard ? 105 : null,
			withCard ? List.of(sampleCard()) : List.of(),
			new PostSellFlow(
				PostSellFeedbackStatus.READY,
				new BigDecimal("69200"),
				LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 27)),
				new BigDecimal("0.0102"),
				new BigDecimal("69500"),
				LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 5))),
			new Counterfactuals(
				PostSellFeedbackStatus.READY,
				new CounterfactualScenario(
					new BigDecimal("69200"), LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 27)), null),
				new CounterfactualScenario(
					new BigDecimal("70800"), LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 5)), null),
				null),
			// 지표는 7번 몫이라 전부 null이고 status만 확정 상태다 — 게이트가 보는 값이 그것뿐이다.
			new PeerComparison(peerStatus, null, null, null, null, null),
			null,
			null,
			null);
	}

	private static HeldPriceMoveItem sampleCard() {
		return new HeldPriceMoveItem(
			12L,
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 20)),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 25)),
			new BigDecimal("-0.018200"),
			115,
			195,
			"11시 20분부터 5분간 1.82% 하락했습니다.",
			List.of(new NewsItem(
				MarketNewsItemType.NEWS,
				"생산 차질",
				"hankyung.com",
				"https://news.example.test/regen",
				LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)))));
	}

	@TestConfiguration
	static class RegenerationTestConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(VIEW_AT.atZone(KST).toInstant(), KST);
		}

		// 실제 OpenAI를 부르지 않는다 (ADR-0011·PRD C-005). 응답을 넣지 않은 회차는 실패로 수렴한다.
		@Bean
		@Primary
		FakeNarrativeGenerator fakeNarrativeGenerator() {
			return new FakeNarrativeGenerator();
		}
	}
}
