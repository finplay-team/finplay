// 고정 Clock + Testcontainers로 코인 매도 회고의 KST 자정 게이트 전이를 실제 원장 위에서 검증한다.
package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.service.CandleInterval;
import com.finplay.api.domain.market.service.CryptoCandleDto;
import com.finplay.api.domain.market.service.FakeCryptoCandleProvider;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.entity.HoldingLot;
import com.finplay.api.domain.portfolio.entity.TradeAllocation;
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.domain.portfolio.repository.TradeAllocationRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

// tasks-275.md 4번 항목의 통합 몫이다 — 분기 케이스는 CryptoPostSellFeedbackReaderTest가 mock으로 보고,
// 이 파일은 그 판정이 실제 컨텍스트의 Clock 빈·실제 원장 위에서도 같은지를 게이트 하나로 확인한다(ADR-0003 —
// mock만으로 검증을 끝내지 않는다).
//
// 주식 게이트 테스트(PostSellFeedbackGateIntegrationTest)는 수정하지 않는다. 코인 게이트는 15:30 장 마감이
// 아니라 KST 자정이고 기준도 "오늘"이 아니라 그 체결의 날짜라, 같은 파일에 두면 두 규칙이 섞여 읽힌다.
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class CryptoPostSellFeedbackGateIntegrationTest {

	private static final String SYMBOL = "GATE275";

	private static final LocalDate SELL_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalDateTime BUY_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(9, 0));
	private static final LocalDateTime SELL_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(12, 0));

	// §C-5 — 게이트는 (매도 체결 KST 날짜 + 1일) 00:00에 열린다.
	private static final LocalDateTime GATE_OPENS_AT = SELL_DATE.plusDays(1).atStartOfDay();

	private static final BigDecimal QUANTITY = new BigDecimal("10");
	private static final BigDecimal SELL_PRICE = new BigDecimal("68500");

	@Autowired
	private PostSellFeedbackReader postSellFeedbackReader;

	@Autowired
	private FakeCryptoCandleProvider cryptoCandleProvider;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private HoldingLotRepository holdingLotRepository;

	@Autowired
	private TradeAllocationRepository tradeAllocationRepository;

	@Autowired
	private TestClock clock;

	private Instrument coin;

	@BeforeEach
	void setUp() {
		cryptoCandleProvider.reset();
		// 매도일 일봉을 심어 둔다 — 게이트가 열린 뒤 값이 실제로 채워지는 것까지 봐야 "상태만 바뀌고 내용은 빈"
		// 구현과 구분된다.
		cryptoCandleProvider.setCandles(SYMBOL, CandleInterval.ONE_DAY, List.of(new CryptoCandleDto(
			SELL_DATE.atStartOfDay(), new BigDecimal("69200"), new BigDecimal("71000"),
			new BigDecimal("67000"), new BigDecimal("69200"), new BigDecimal("1.5"))));
		coin = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, SYMBOL, "게이트테스트코인", BigDecimal.valueOf(1), 5_000L, true, SELL_AT));
	}

	@Test
	@DisplayName("코인 회고의 게이트가 매도 다음 날 자정에 열리고 이틀 뒤 조회에서도 READY로 유지된다")
	void opensAtTheNextMidnightAndStaysOpenOnLaterDays() {
		Trade sellTrade = givenOwnCryptoSellTrade();

		// 직전 — 매도 당일 23:59.
		clock.set(GATE_OPENS_AT.minusMinutes(1));
		PostSellFeedbackResponse beforeGate = read(sellTrade);
		assertThat(beforeGate.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(beforeGate.postSellFlow().closePrice()).isNull();
		assertThat(beforeGate.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(beforeGate.counterfactuals().atClose()).isNull();

		// 직후 — 다음 날 00:00 정각.
		clock.set(GATE_OPENS_AT);
		PostSellFeedbackResponse atGate = read(sellTrade);
		assertThat(atGate.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(atGate.postSellFlow().closePrice()).isEqualByComparingTo("69200");
		assertThat(atGate.postSellFlow().closeAt())
			.isEqualTo(LocalDateTime.of(SELL_DATE, LocalTime.of(23, 59)));
		assertThat(atGate.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(atGate.counterfactuals().atClose().price()).isEqualByComparingTo("69200");

		// 이틀 뒤 — 기준이 "오늘 자정"이면 여기서 NOT_YET으로 되돌아간다.
		clock.set(GATE_OPENS_AT.plusDays(2).plusHours(9));
		PostSellFeedbackResponse daysLater = read(sellTrade);
		assertThat(daysLater.postSellFlow().status())
			.as("게이트 기준은 오늘이 아니라 그 체결의 날짜다")
			.isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(daysLater.postSellFlow().closePrice()).isEqualByComparingTo("69200");
		assertThat(daysLater.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
	}

	// --- 픽스처 ---

	private PostSellFeedbackResponse read(Trade sellTrade) {
		return postSellFeedbackReader.read(sellTrade.getAccount().getUser().getId(), sellTrade.getId());
	}

	private Trade givenOwnCryptoSellTrade() {
		User user = userRepository.saveAndFlush(
			User.create("crypto-gate@finplay.com", "hash", "cgate", BUY_AT));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BUY_AT));
		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, coin, BUY_AT));

		Order buyOrder = orderRepository.saveAndFlush(Order.create(
			user, account, coin, OrderSide.BUY, OrderType.MARKET, QUANTITY, "idem-gate-buy", "a".repeat(64), BUY_AT));
		Trade buyTrade = tradeRepository.saveAndFlush(Trade.of(
			buyOrder, account, coin, null, OrderSide.BUY, new BigDecimal("70000"), QUANTITY,
			700_000L, 105L, null, BUY_AT, BUY_AT));
		HoldingLot lot = holdingLotRepository.saveAndFlush(HoldingLot.create(
			holding, buyTrade, QUANTITY, new BigDecimal("70000"), 105L, BUY_AT, BUY_AT));

		Order sellOrder = orderRepository.saveAndFlush(Order.create(
			user, account, coin, OrderSide.SELL, OrderType.MARKET, QUANTITY, "idem-gate-sell", "b".repeat(64),
			SELL_AT));
		Trade sellTrade = tradeRepository.saveAndFlush(Trade.of(
			sellOrder, account, coin, null, OrderSide.SELL, SELL_PRICE, QUANTITY,
			685_000L, 342L, -15_447L, SELL_AT, SELL_AT));

		lot.consume(QUANTITY);
		holdingLotRepository.saveAndFlush(lot);
		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(sellTrade, lot, QUANTITY, 700_000L, 105L, SELL_AT));
		return sellTrade;
	}
}
