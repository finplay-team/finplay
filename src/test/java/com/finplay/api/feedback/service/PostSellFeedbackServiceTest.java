// 매도 회고 서술의 최초 생성·재사용·템플릿 폴백과 저장 경계 오케스트레이션을 검증하는 단위 테스트다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.feedback.config.FeedbackLlmProperties;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.PostSellFeedbackStatus;
import com.finplay.api.feedback.domain.TradeFeedback;
import com.finplay.api.feedback.dto.response.Counterfactuals;
import com.finplay.api.feedback.dto.response.CounterfactualScenario;
import com.finplay.api.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.feedback.dto.response.NewsItem;
import com.finplay.api.feedback.dto.response.PeerComparison;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.feedback.dto.response.PostSellFlow;
import com.finplay.api.feedback.repository.TradeFeedbackRepository;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

// 이슈 #208 4번 항목이 소유한 완료 조건이다 — 상태값 ⑤(LLM 실패에도 READY·TEMPLATE)와 "LLM 실패에도 수치·
// 파생 사실이 200". 종단(최초 생성 → 재사용, 원장 불변)은 PostSellFeedbackNarrativeIntegrationTest가 실제
// 대역 생성기와 실 DB로 맡고, 수치·파생 사실·게이트 자체는 reader 쪽 세 파일이 맡는다.
//
// narrativeStatus는 상수 READY라 그것만 보는 단정은 공허하다 — 그래서 이 파일은 "서술이 실제로 채워지는가"와
// "LLM을 몇 번 부르는가"를 함께 본다. 폴백 문장 자체는 NarrativeService의 책임이라 여기서는 그 결과를 대역으로
// 고정한다(실제 외부 API를 부르지 않는다, ADR-0011).
class PostSellFeedbackServiceTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Long USER_ID = 1L;
	private static final Long SELL_TRADE_ID = 2L;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 16, 0);

	private static final String LLM_NARRATIVE = "09시 30분 매수는 이날 하락 구간보다 1시간 55분 앞섰습니다.";
	private static final String TEMPLATE_NARRATIVE = "09시 30분에 70,000원에 매수해 14시 40분에 68,500원에 매도했습니다.";

	private final PostSellFeedbackReader postSellFeedbackReader = mock(PostSellFeedbackReader.class);

	private final NarrativeService narrativeService = mock(NarrativeService.class);

	private final TradeFeedbackWriter tradeFeedbackWriter = mock(TradeFeedbackWriter.class);

	private final TradeFeedbackRepository tradeFeedbackRepository = mock(TradeFeedbackRepository.class);

	// 재생성 누적 상한만 쓰이므로 나머지는 application.yml 기본값과 같은 값으로 둔다(§C-7).
	private static final FeedbackLlmProperties LLM_PROPERTIES = new FeedbackLlmProperties("gpt-5.4-mini", 20, 512, 1,
		3);

	private final PostSellFeedbackService postSellFeedbackService = new PostSellFeedbackService(
		postSellFeedbackReader, narrativeService, tradeFeedbackWriter, tradeFeedbackRepository,
		LLM_PROPERTIES, Clock.fixed(NOW.atZone(KST).toInstant(), KST));

	// --- 최초 생성 ---

	@Test
	@DisplayName("기존 서술이 없으면 만들어 저장하고 응답에 실는다 — narrativeStatus는 READY다")
	void createsAndStoresTheNarrativeOnTheFirstQuery() {
		givenFacts(factsWithoutNarrative());
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		// 저장은 그 회원·그 체결로만 나가고 생성 시각은 주입된 시계다.
		verify(tradeFeedbackWriter).create(
			eq(USER_ID), eq(SELL_TRADE_ID), eq(NarrativeResultDto.llm(LLM_NARRATIVE)), eq(NOW));
	}

	// --- 재사용 (최초 1회만 부른다) ---

	@Test
	@DisplayName("기존 서술이 있으면 그것을 쓰고 LLM을 부르지 않으며 저장하지도 않는다")
	void reusesTheStoredNarrativeWithoutCallingTheLlmAgain() {
		givenFacts(factsWithoutNarrative());
		when(tradeFeedbackRepository.findByTradeId(SELL_TRADE_ID))
			.thenReturn(Optional.of(storedFeedback(TEMPLATE_NARRATIVE, NarrativeSource.TEMPLATE)));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo(TEMPLATE_NARRATIVE);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.TEMPLATE);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

	// 두 번째 조회에서 또 부르면 사용자가 매번 2.5초를 기다리고 같은 체결의 문장이 조회마다 달라진다.
	@Test
	@DisplayName("같은 체결을 두 번 조회하면 LLM 호출이 정확히 1회이고 두 응답의 문장이 같다")
	void callsTheLlmExactlyOnceAcrossTwoQueriesOfTheSameTrade() {
		givenFacts(factsWithoutNarrative());
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));
		// 첫 조회는 기존 행이 없고, 두 번째 조회는 방금 저장된 행을 본다.
		when(tradeFeedbackRepository.findByTradeId(SELL_TRADE_ID))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(storedFeedback(LLM_NARRATIVE, NarrativeSource.LLM)));

		PostSellFeedbackResponse first = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);
		PostSellFeedbackResponse second = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		verify(narrativeService).resolvePostSellNarrative(any());
		verify(tradeFeedbackWriter).create(any(), any(), any(), any());
		assertThat(second.narrative()).isEqualTo(first.narrative());
		assertThat(second.narrativeSource()).isEqualTo(first.narrativeSource());
	}

	// --- 상태값 ⑤ · LLM 실패 ---

	// narrativeStatus는 상수라 무조건 통과한다 — 그래서 이 테스트의 값은 "실패해도 문장이 비지 않는다"와
	// "source가 TEMPLATE로 구분된다"에 있다. UNAVAILABLE은 이 엔드포인트에 존재하지 않는다(§C-4).
	@Test
	@DisplayName("LLM이 실패해 템플릿으로 대체돼도 narrativeStatus가 READY이고 서술이 비지 않는다")
	void keepsReadyWithTemplateSourceWhenTheLlmFails() {
		givenFacts(factsWithoutNarrative());
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.template(TEMPLATE_NARRATIVE));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.TEMPLATE);
		assertThat(response.narrative()).isNotBlank();
		assertThat(response.narrativeStatus()).isNotIn(
			PostSellFeedbackStatus.NOT_YET, PostSellFeedbackStatus.NO_EVENT,
			PostSellFeedbackStatus.INSUFFICIENT_SAMPLE);
	}

	@Test
	@DisplayName("템플릿으로 대체돼도 수치 요약·파생 사실·매도 후 흐름이 그대로 응답에 남는다")
	void keepsEveryNumberAndDerivedFactWhenTheNarrativeFallsBackToTheTemplate() {
		PostSellFeedbackResponse facts = factsWithoutNarrative();
		givenFacts(facts);
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.template(TEMPLATE_NARRATIVE));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.buyPrice()).isEqualByComparingTo(facts.buyPrice());
		assertThat(response.sellPrice()).isEqualByComparingTo(facts.sellPrice());
		assertThat(response.returnRate()).isEqualByComparingTo(facts.returnRate());
		assertThat(response.holdingMinutes()).isEqualTo(facts.holdingMinutes());
		assertThat(response.holdHighPrice()).isEqualByComparingTo(facts.holdHighPrice());
		assertThat(response.sellVsHighRate()).isEqualByComparingTo(facts.sellVsHighRate());
		assertThat(response.buyToNewsMinutes()).isEqualTo(facts.buyToNewsMinutes());
		assertThat(response.priceMoves()).isEqualTo(facts.priceMoves());
		assertThat(response.postSellFlow()).isEqualTo(facts.postSellFlow());
		assertThat(response.counterfactuals()).isEqualTo(facts.counterfactuals());
		assertThat(response.peerComparison()).isEqualTo(facts.peerComparison());
	}

	// --- UNIQUE(trade_id) 동시 삽입 ---

	// 새로고침 연타면 두 요청이 각자 "기존 행 없음"을 보고 저장을 시도한다. 흡수하지 않으면 GET이 500이 된다.
	@Test
	@DisplayName("동시 삽입으로 UNIQUE(trade_id)가 충돌해도 500이 아니라 방금 만든 문장으로 200이다")
	void absorbsTheUniqueViolationRaisedByAConcurrentInsert() {
		givenFacts(factsWithoutNarrative());
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));
		when(tradeFeedbackWriter.create(any(), any(), any(), any()))
			.thenThrow(new DataIntegrityViolationException("Duplicate entry for key 'uk_trade_feedbacks_trade_id'"));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
	}

	// 위 테스트의 짝 — 같은 catch절에 들어오지만 행이 안 생긴 경우다. 무결성 위반이 중복이 아니면(FK·NOT NULL)
	// 재조회가 빈 결과이므로 저장은 실패로 남지만, 그렇다고 조회를 500으로 떨어뜨리지는 않는다. 판정을 예외 타입이
	// 아니라 "행이 실제로 있는가"로 하기 때문에 이 경로가 존재한다 — 재조회가 일어나는지까지 함께 고정한다.
	// 로그 문구는 단정하지 않는다(이 저장소에 로그 단정 관례가 없다). 행동으로 고정한다.
	@Test
	@DisplayName("중복이 아닌 무결성 위반으로 저장이 실패하고 행도 없으면 예외를 내보내지 않고 정상 응답을 준다")
	void stillReturnsTheResponseWhenANonDuplicateIntegrityViolationLeavesNoRow() {
		PostSellFeedbackResponse facts = factsWithoutNarrative();
		givenFacts(facts);
		// 최초 조회와 저장 실패 후 재조회가 모두 빈 결과다 — 중복이 아니므로 행이 생기지 않았다.
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));
		when(tradeFeedbackWriter.create(any(), any(), any(), any()))
			.thenThrow(new DataIntegrityViolationException(
				"Cannot add or update a child row: a foreign key constraint fails (`fk_trade_feedbacks_trade`)"));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		// 예외가 밖으로 나가지 않고 방금 만든 문장과 수치가 그대로 200으로 나간다.
		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.returnRate()).isEqualByComparingTo(facts.returnRate());
		assertThat(response.priceMoves()).isEqualTo(facts.priceMoves());
		// 그 경로를 실제로 밟았다 — 저장을 시도했고 실패 후 행을 다시 읽었다(최초 조회 + 재조회 = 2회).
		verify(tradeFeedbackWriter).create(eq(USER_ID), eq(SELL_TRADE_ID), any(), eq(NOW));
		verify(tradeFeedbackRepository, times(2)).findByTradeId(SELL_TRADE_ID);
	}

	// 유니크 충돌만 흡수한다 — 다른 DB 오류를 함께 삼키면 저장이 조용히 안 되는 상태가 로그도 없이 굳는다.
	// 위 두 테스트가 catch절 안쪽을 보고, 이 테스트는 catch 폭 밖의 예외가 그대로 전파되는지를 본다.
	@Test
	@DisplayName("유니크 충돌이 아닌 저장 실패는 삼키지 않는다")
	void doesNotSwallowOtherPersistenceFailures() {
		givenFacts(factsWithoutNarrative());
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));
		when(tradeFeedbackWriter.create(any(), any(), any(), any()))
			.thenThrow(new IllegalStateException("커넥션 없음"));

		assertThatThrownBy(() -> postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID))
			.isInstanceOf(IllegalStateException.class);
	}

	// --- 검증 순서 (서술 생성보다 먼저다) ---

	@Test
	@DisplayName("reader가 404·403·400으로 거부하면 LLM을 부르지 않고 저장도 하지 않는다")
	void neverGeneratesANarrativeWhenTheReaderRejectsTheRequest() {
		when(postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		assertThatThrownBy(() -> postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.FORBIDDEN));

		verifyNoInteractions(narrativeService, tradeFeedbackWriter, tradeFeedbackRepository);
	}

	// --- 프롬프트 입력 조립 ---

	@Test
	@DisplayName("프롬프트에 수치·파생 사실·카드가 실리고 반사실은 애초에 자리가 없으며 집단 비교는 null이다")
	void mapsFactsIntoThePromptInputWithoutCounterfactuals() {
		givenFacts(factsWithoutNarrative());
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.llm(LLM_NARRATIVE));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		ArgumentCaptor<PostSellPromptDto> captor = ArgumentCaptor.forClass(PostSellPromptDto.class);
		verify(narrativeService).resolvePostSellNarrative(captor.capture());
		PostSellPromptDto prompt = captor.getValue();
		// 시각은 HH:mm만 쓴다 — 원본 거래일 날짜는 문장에 등장하지 않는다.
		assertThat(prompt.buyAt()).isEqualTo(LocalTime.of(9, 30));
		assertThat(prompt.sellAt()).isEqualTo(LocalTime.of(14, 40));
		assertThat(prompt.buyPrice()).isEqualByComparingTo("70000");
		assertThat(prompt.sellPrice()).isEqualByComparingTo("68500");
		assertThat(prompt.realizedPnl()).isEqualTo(-15_207L);
		assertThat(prompt.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(prompt.buyToNewsMinutes()).isEqualTo(105);
		// buyToNewsMinutes와 firstNewsAt은 같은 근거 기사 하나에서 나온다 — 한쪽만 채우면 기준 시각이 사라진다.
		assertThat(prompt.firstNewsAt()).isEqualTo(LocalTime.of(11, 15));
		assertThat(prompt.priceMoves()).singleElement().satisfies(move -> {
			assertThat(move.windowEnd()).isEqualTo(LocalTime.of(11, 25));
			assertThat(move.minutesAfterBuy()).isEqualTo(115);
			assertThat(move.sources()).singleElement()
				.satisfies(source -> assertThat(source.disclosure()).isFalse());
		});
		assertThat(prompt.closePrice()).isEqualByComparingTo("69200");
		assertThat(prompt.sellToCloseRate()).isEqualByComparingTo("0.0102");
		// 집단 비교는 항상 NOT_YET이라 넘길 값이 없는 것이 정상 상태다 — 7번이 판정을 붙이면 값이 흘러 들어온다.
		assertThat(prompt.holderCount()).isNull();
		assertThat(prompt.soldWithin30MinRate()).isNull();
		assertThat(prompt.medianMinutesToSell()).isNull();
		assertThat(prompt.yourMinutesToSell()).isNull();
	}

	@Test
	@DisplayName("근거 기사가 없으면 buyToNewsMinutes와 firstNewsAt이 함께 null이다")
	void leavesBothNewsFieldsNullWhenThereIsNoSource() {
		givenFacts(factsWithoutNarrative(false));
		givenNoStoredNarrative();
		givenGenerated(NarrativeResultDto.template(TEMPLATE_NARRATIVE));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		ArgumentCaptor<PostSellPromptDto> captor = ArgumentCaptor.forClass(PostSellPromptDto.class);
		verify(narrativeService).resolvePostSellNarrative(captor.capture());
		assertThat(captor.getValue().buyToNewsMinutes()).isNull();
		assertThat(captor.getValue().firstNewsAt()).isNull();
		assertThat(captor.getValue().priceMoves()).isEmpty();
	}

	// --- 재생성 게이트 (완료 조건 8·10번) ---

	// 게이트가 열린 뒤 첫 조회에서 1회 갈아 끼운다. 성공하면 narrative_finalized가 닫히므로 두 번째 조회는
	// 재생성하지 않는다 — 아래 두 테스트가 짝이다.
	@Test
	@DisplayName("게이트가 열리면 첫 조회에서 재생성하고 성공 저장을 부른다")
	void regeneratesOnceWhenTheGateIsOpen() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.READY));
		givenStored(pendingFeedback(0));
		givenGenerated(NarrativeResultDto.llm("재생성된 문장입니다."));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo("재생성된 문장입니다.");
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		verify(tradeFeedbackWriter).applyRegenerated(eq(SELL_TRADE_ID), any(), eq(NOW));
		verify(tradeFeedbackWriter, never()).recordFailedRegeneration(any());
		verify(tradeFeedbackWriter, never()).create(any(), any(), any(), any());
	}

	@Test
	@DisplayName("확정된 서술은 게이트가 열려 있어도 재생성하지 않는다")
	void neverRegeneratesAnAlreadyFinalizedNarrative() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.READY));
		givenStored(finalizedFeedback());

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo("확정된 문장입니다.");
		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

	// 완료 조건 10번 — 카드 0건이면 price_move_peer_stats 행이 애초에 생기지 않는다. 게이트를 "확정 집계 행
	// 존재"로 판정한 구현은 이 경우에 영원히 재생성하지 않는데, 운영에서 가장 흔한 경우다.
	@Test
	@DisplayName("집단 비교가 NO_EVENT·INSUFFICIENT_SAMPLE이어도 확정으로 쳐서 재생성한다")
	void treatsNoEventAndInsufficientSampleAsSettled() {
		for (PostSellFeedbackStatus settled : List.of(
			PostSellFeedbackStatus.NO_EVENT, PostSellFeedbackStatus.INSUFFICIENT_SAMPLE,
			PostSellFeedbackStatus.READY)) {
			PostSellFeedbackService service = newService();
			givenFacts(gateOpenFacts(settled));
			givenStored(pendingFeedback(0));
			givenGenerated(NarrativeResultDto.llm("재생성된 문장입니다."));

			PostSellFeedbackResponse response = service.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

			assertThat(response.narrative())
				.as("peerComparison.status=%s는 확정이므로 게이트가 열린다", settled)
				.isEqualTo("재생성된 문장입니다.");
		}
	}

	@Test
	@DisplayName("집단 비교가 NOT_YET이면 매도 후 흐름이 READY여도 재생성하지 않는다")
	void keepsTheGateClosedWhilePeerComparisonIsNotYet() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.NOT_YET));
		givenStored(pendingFeedback(0));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

	// 매도 후 흐름만 보고 열면 15:30~집계 사이에 조회한 사용자가 집단 비교 없는 문장으로 굳는다 — 재생성이
	// 1회뿐이라 되돌릴 기회가 없다.
	@Test
	@DisplayName("매도 후 흐름이 NOT_YET이면 집단 비교가 확정이어도 재생성하지 않는다")
	void keepsTheGateClosedWhilePostSellFlowIsNotYet() {
		givenFacts(factsWithoutNarrative(
			true, PostSellFeedbackStatus.NOT_YET, PostSellFeedbackStatus.NO_EVENT));
		givenStored(pendingFeedback(0));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

	// --- 재생성 실패와 누적 상한 (완료 조건 9번) ---

	// 템플릿 폴백을 실패로 취급한다 — 템플릿 문장에는 매도 후 흐름·집단 비교가 없어 덮으면 서술이 빈약해진다.
	@Test
	@DisplayName("재생성이 템플릿으로 폴백하면 기존 서술을 유지하고 실패만 누적한다")
	void keepsTheStoredNarrativeWhenRegenerationFallsBackToTheTemplate() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.NO_EVENT));
		givenStored(pendingFeedback(0));
		givenGenerated(NarrativeResultDto.template("템플릿 문장입니다."));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		// 기존 서술이 그대로 나간다 — 템플릿으로 덮지 않는다.
		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		verify(tradeFeedbackWriter).recordFailedRegeneration(SELL_TRADE_ID);
		verify(tradeFeedbackWriter, never()).applyRegenerated(any(), any(), any());
	}

	@Test
	@DisplayName("누적 시도가 상한 미만이면 다시 시도한다")
	void retriesWhileTheCumulativeCountIsBelowTheLimit() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.NO_EVENT));
		givenStored(pendingFeedback(LLM_PROPERTIES.maxNarrativeRetry() - 1));
		givenGenerated(NarrativeResultDto.template("템플릿 문장입니다."));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		verify(narrativeService).resolvePostSellNarrative(any());
		verify(tradeFeedbackWriter).recordFailedRegeneration(SELL_TRADE_ID);
	}

	// 상한에 도달하면 게이트가 열려 있어도 LLM을 부르지 않는다 — 여기서 부르면 실패하는 체결 하나가 조회마다
	// LLM을 호출하는데 응답은 정상 200이라 아무 신호도 남지 않는다. 바로 위 테스트와 짝이다.
	@Test
	@DisplayName("누적 시도가 상한에 도달하면 LLM을 부르지 않는다")
	void stopsRetryingWhenTheCumulativeLimitIsReached() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.NO_EVENT));
		givenStored(pendingFeedback(LLM_PROPERTIES.maxNarrativeRetry()));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

	// 판정 순서가 narrativeFinalized → 누적 상한 → 게이트다. 상한을 게이트 뒤에 두면 상한을 넘긴 체결이
	// 게이트가 열린 동안 계속 LLM을 부른다.
	@Test
	@DisplayName("누적 시도가 상한을 넘었으면 게이트 계산 전에 멈춘다")
	void checksTheCumulativeLimitBeforeTheGate() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.READY));
		givenStored(pendingFeedback(LLM_PROPERTIES.maxNarrativeRetry() + 1));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		verifyNoInteractions(narrativeService, tradeFeedbackWriter);
	}

	// 게이트가 열렸으면 프롬프트에 매도 후 흐름 줄이 붙는다 — 매핑을 따로 만들면 그 줄이 빠진 프롬프트로
	// 재생성해 게이트가 무의미해진다.
	@Test
	@DisplayName("재생성 프롬프트에 매도 후 흐름 값이 실린다")
	void feedsThePostSellFlowIntoTheRegenerationPrompt() {
		givenFacts(gateOpenFacts(PostSellFeedbackStatus.NO_EVENT));
		givenStored(pendingFeedback(0));
		givenGenerated(NarrativeResultDto.llm("재생성된 문장입니다."));

		postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID);

		ArgumentCaptor<PostSellPromptDto> captor = ArgumentCaptor.forClass(PostSellPromptDto.class);
		verify(narrativeService).resolvePostSellNarrative(captor.capture());
		assertThat(captor.getValue().closePrice()).isEqualByComparingTo("69200");
		assertThat(captor.getValue().sellToCloseRate()).isEqualByComparingTo("0.0102");
	}

	// --- 트랜잭션 경계 (구조 단정) ---

	// LLM 호출이 중앙값 2.5초라 여기에 트랜잭션을 걸면 그 시간 동안 커넥션을 쥔다. 편의로 애노테이션을 붙이는
	// 회귀는 동작으로 드러나지 않으므로(느려지기만 한다) 구조를 단정으로 고정한다.
	@Test
	@DisplayName("PostSellFeedbackService에는 클래스·메서드 어디에도 @Transactional이 없다")
	void neverWrapsTheOrchestrationInATransaction() throws Exception {
		assertThat(PostSellFeedbackService.class.getAnnotation(Transactional.class)).isNull();
		assertThat(PostSellFeedbackService.class.getAnnotation(jakarta.transaction.Transactional.class)).isNull();

		Method entryPoint = PostSellFeedbackService.class.getMethod(
			"getPostSellFeedback", Long.class, Long.class);
		assertThat(entryPoint.getAnnotation(Transactional.class)).isNull();
		assertThat(entryPoint.getAnnotation(jakarta.transaction.Transactional.class)).isNull();
	}

	// --- 픽스처 ---

	private void givenFacts(PostSellFeedbackResponse facts) {
		when(postSellFeedbackReader.read(USER_ID, SELL_TRADE_ID)).thenReturn(facts);
	}

	private void givenNoStoredNarrative() {
		when(tradeFeedbackRepository.findByTradeId(SELL_TRADE_ID)).thenReturn(Optional.empty());
	}

	private void givenGenerated(NarrativeResultDto resolved) {
		when(narrativeService.resolvePostSellNarrative(any())).thenReturn(resolved);
	}

	private PostSellFeedbackService newService() {
		return new PostSellFeedbackService(
			postSellFeedbackReader, narrativeService, tradeFeedbackWriter, tradeFeedbackRepository,
			LLM_PROPERTIES, Clock.fixed(NOW.atZone(KST).toInstant(), KST));
	}

	private void givenStored(TradeFeedback feedback) {
		when(tradeFeedbackRepository.findByTradeId(SELL_TRADE_ID)).thenReturn(Optional.of(feedback));
	}

	/** 아직 확정되지 않은 행 — 실패를 {@code attempts}회 누적한 상태를 실제 전이 메서드로 만든다. */
	private static TradeFeedback pendingFeedback(int attempts) {
		TradeFeedback feedback = storedFeedback(LLM_NARRATIVE, NarrativeSource.LLM);
		for (int i = 0; i < attempts; i++) {
			feedback.recordFailedRegeneration();
		}
		return feedback;
	}

	/** 재생성 게이트를 이미 통과해 확정된 행. */
	private static TradeFeedback finalizedFeedback() {
		TradeFeedback feedback = storedFeedback(LLM_NARRATIVE, NarrativeSource.LLM);
		feedback.applyRegeneratedNarrative("확정된 문장입니다.", NarrativeSource.LLM, NOW.minusMinutes(10));
		return feedback;
	}

	// 엔티티를 mock으로 만들지 않는다 — 실제 팩토리로 만들어 값이 담긴 객체를 쓴다(docs/conventions.md).
	// 이 경로는 서술 두 값만 읽으므로 연관 체결은 필요하지 않다.
	private static TradeFeedback storedFeedback(String narrative, NarrativeSource source) {
		return TradeFeedback.create(null, narrative, source, NOW.minusMinutes(30));
	}

	private static PostSellFeedbackResponse factsWithoutNarrative() {
		return factsWithoutNarrative(true);
	}

	private static PostSellFeedbackResponse factsWithoutNarrative(boolean withCard) {
		return factsWithoutNarrative(withCard, PostSellFeedbackStatus.READY, PostSellFeedbackStatus.NOT_YET);
	}

	/** 재생성 게이트를 여는 픽스처 — 매도 후 흐름은 READY이고 집단 비교가 확정 상태다(§C-5). */
	private static PostSellFeedbackResponse gateOpenFacts(PostSellFeedbackStatus peerStatus) {
		return factsWithoutNarrative(true, PostSellFeedbackStatus.READY, peerStatus);
	}

	/**
	 * reader가 돌려주는 형태 — 서술 세 값이 {@code null}이고 나머지는 계약 예시 그대로다. 게이트가 열린 뒤라
	 * 매도 후 흐름·반사실이 채워져 있고 집단 비교는 {@code NOT_YET}이다.
	 */
	private static PostSellFeedbackResponse factsWithoutNarrative(
		boolean withCard, PostSellFeedbackStatus flowStatus, PostSellFeedbackStatus peerStatus) {
		boolean marketClosed = flowStatus == PostSellFeedbackStatus.READY;
		return new PostSellFeedbackResponse(
			SELL_TRADE_ID,
			1L,
			"005930",
			"삼성전자",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 30)),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 40)),
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
			marketClosed
				? new PostSellFlow(
					PostSellFeedbackStatus.READY,
					new BigDecimal("69200"),
					LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 27)),
					new BigDecimal("0.0102"),
					new BigDecimal("69500"),
					LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 5)))
				: new PostSellFlow(PostSellFeedbackStatus.NOT_YET, null, null, null, null, null),
			new Counterfactuals(
				PostSellFeedbackStatus.READY,
				new CounterfactualScenario(
					new BigDecimal("69200"), LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 27)), null),
				new CounterfactualScenario(
					new BigDecimal("70800"), LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 5)), null),
				null),
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
				"https://news.example.test/1",
				LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)))));
	}
}
