package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class PriceMoveCardServiceTest {

	private static final Long INSTRUMENT_ID = 1L;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 28);

	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 7, 27);

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 8, 45);

	private final PriceMoveEventRepository priceMoveEventRepository = mock(PriceMoveEventRepository.class);

	private final PriceMoveCardWriter priceMoveCardWriter = mock(PriceMoveCardWriter.class);

	private final NewsMatcher newsMatcher = mock(NewsMatcher.class);

	private final NarrativeService narrativeService = mock(NarrativeService.class);

	private final Instrument instrument = stockInstrument();

	private final PriceMoveCardService service = new PriceMoveCardService(
		priceMoveEventRepository,
		priceMoveCardWriter,
		newsMatcher,
		narrativeService,
		Clock.fixed(NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")));

	private static Instrument stockInstrument() {
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.ONE, 10000L, true, LocalDateTime.now());
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		return instrument;
	}

	private MarketNewsItem newsAt(LocalDateTime publishedAt) {
		return MarketNewsItem.create(
			instrument,
			MarketNewsItemType.NEWS,
			publishedAt.toString(),
			"테스트경제",
			"https://news.example.com/" + publishedAt,
			publishedAt,
			publishedAt.plusMinutes(30));
	}

	private static PriceMoveDetectionDto intraday(LocalTime windowEnd) {
		return new PriceMoveDetectionDto(
			PriceMoveEventType.INTRADAY,
			windowEnd.minusMinutes(5),
			windowEnd,
			new BigDecimal("-0.018200"),
			new BigDecimal("3.2500"));
	}

	private static PriceMoveDetectionDto openingGap(LocalTime firstCandleTime) {
		return new PriceMoveDetectionDto(
			PriceMoveEventType.OPENING_GAP,
			firstCandleTime,
			firstCandleTime,
			new BigDecimal("0.030000"),
			new BigDecimal("3.0000"));
	}

	private PriceMoveEvent confirmAndCapture(
		PriceMoveDetectionDto detection, NarrativeResultDto narrative, List<MarketNewsItem> sources) {
		when(newsMatcher.match(INSTRUMENT_ID, ORIGIN_TRADE_DATE, detection)).thenReturn(sources);
		when(narrativeService.resolvePriceMoveNarrative(any())).thenReturn(narrative);
		when(priceMoveCardWriter.persist(any(), anyList()))
			.thenAnswer(invocation -> invocation.getArgument(0));

		assertThat(service.confirmStockCard(instrument, ORIGIN_TRADE_DATE, detection)).isPresent();

		ArgumentCaptor<PriceMoveEvent> cardCaptor = ArgumentCaptor.forClass(PriceMoveEvent.class);
		verify(priceMoveCardWriter).persist(cardCaptor.capture(), anyList());
		return cardCaptor.getValue();
	}

	@Nested
	@DisplayName("게이트 ③④⑤ reveal_time 계산 (§노출 판정)")
	class RevealTime {

		@Test
		@DisplayName("시가 갭 카드는 전장 근거를 09:00으로 당긴다 — 첫 분봉이 09:03이어도 09:00이다")
		void openingGapClampsPreMarketSourceToMarketOpen() {
			PriceMoveDetectionDto detection = openingGap(LocalTime.of(9, 3));
			LocalDateTime previousEvening = LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 40));

			PriceMoveEvent card = confirmAndCapture(
				detection, NarrativeResultDto.llm("시가 갭 문장"), List.of(newsAt(previousEvening)));

			assertThat(card.getRevealTime()).isEqualTo(LocalTime.of(9, 0));
			assertThat(card.getRevealTime()).isNotEqualTo(previousEvening.toLocalTime());
			assertThat(card.getRevealTime()).isNotEqualTo(LocalTime.of(9, 4));
			assertThat(card.getWindowStart()).isEqualTo(LocalTime.of(9, 3));
			assertThat(card.getWindowEnd()).isEqualTo(LocalTime.of(9, 3));
		}

		@Test
		@DisplayName("장중 카드는 windowEnd + 1분이다 — 근거가 그보다 이르면 오프셋이 이긴다")
		void intradayAddsOneMinuteOffsetToWindowEnd() {
			PriceMoveDetectionDto detection = intraday(LocalTime.of(11, 25));
			LocalDateTime earlierSource = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15));

			PriceMoveEvent card = confirmAndCapture(
				detection, NarrativeResultDto.llm("장중 문장"), List.of(newsAt(earlierSource)));

			assertThat(card.getRevealTime()).isEqualTo(LocalTime.of(11, 26));
			assertThat(card.getRevealTime()).isNotEqualTo(LocalTime.of(11, 25));
			assertThat(card.getRevealTime()).isNotEqualTo(LocalTime.of(11, 15));
		}

		@Test
		@DisplayName("근거가 windowEnd + 1분보다 늦으면 revealTime이 그 발행시각까지 밀린다")
		void intradayPushesRevealTimeToTheLatestSourceWhenItIsLaterThanTheOffset() {
			PriceMoveDetectionDto detection = intraday(LocalTime.of(11, 25));
			List<MarketNewsItem> sources = List.of(
				newsAt(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15))),
				newsAt(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 29))));

			PriceMoveEvent card = confirmAndCapture(detection, NarrativeResultDto.llm("장중 문장"), sources);

			assertThat(card.getRevealTime()).isEqualTo(LocalTime.of(11, 29));
			assertThat(card.getRevealTime()).isNotEqualTo(LocalTime.of(11, 26));
		}

		@Test
		@DisplayName("장중 카드의 오프셋과 클램프가 같은 max 안에 있다 — 이른 근거가 revealTime을 당기지 않는다")
		void intradayNeverRevealsEarlierThanTheOffset() {
			PriceMoveDetectionDto detection = intraday(LocalTime.of(9, 5));
			LocalDateTime preMarket = LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 40));

			PriceMoveEvent card = confirmAndCapture(
				detection, NarrativeResultDto.llm("장중 문장"), List.of(newsAt(preMarket)));

			assertThat(card.getRevealTime()).isEqualTo(LocalTime.of(9, 6));
		}
	}

	@Nested
	@DisplayName("탐지 ⑥ 근거가 없으면 카드를 만들지 않는다 (FEED-003)")
	class NoSources {

		@Test
		@DisplayName("근거가 0건이면 empty()이고 NarrativeService를 한 번도 부르지 않는다")
		void doesNotCreateCardAndNeverCallsNarrativeServiceWhenThereIsNoSource() {
			PriceMoveDetectionDto detection = intraday(LocalTime.of(11, 25));
			when(newsMatcher.match(INSTRUMENT_ID, ORIGIN_TRADE_DATE, detection)).thenReturn(List.of());

			Optional<PriceMoveEvent> confirmed = service.confirmStockCard(instrument, ORIGIN_TRADE_DATE, detection);

			assertThat(confirmed).isEmpty();
			verifyNoInteractions(narrativeService);
			verifyNoInteractions(priceMoveCardWriter);
		}

		@Test
		@DisplayName("시가 갭도 같다 — 전장 근거가 0건이면 카드를 만들지 않는다")
		void doesNotCreateOpeningGapCardWhenThereIsNoPreMarketSource() {
			PriceMoveDetectionDto detection = openingGap(LocalTime.of(9, 0));
			when(newsMatcher.match(INSTRUMENT_ID, ORIGIN_TRADE_DATE, detection)).thenReturn(List.of());

			assertThat(service.confirmStockCard(instrument, ORIGIN_TRADE_DATE, detection)).isEmpty();
			verifyNoInteractions(narrativeService);
			verifyNoInteractions(priceMoveCardWriter);
		}
	}

	@Nested
	@DisplayName("중복 판정이 서술보다 먼저다 (§실패 처리)")
	class DuplicateCheck {

		@Test
		@DisplayName("이미 있는 카드면 근거 매칭도 서술 생성도 하지 않는다")
		void skipsMatchingAndNarrationWhenTheCardAlreadyExists() {
			PriceMoveDetectionDto detection = intraday(LocalTime.of(11, 25));
			when(priceMoveEventRepository.existsByInstrumentIdAndOriginTradeDateAndEventTypeAndWindowStart(
				INSTRUMENT_ID, ORIGIN_TRADE_DATE, PriceMoveEventType.INTRADAY, LocalTime.of(11, 20)))
				.thenReturn(true);

			assertThat(service.confirmStockCard(instrument, ORIGIN_TRADE_DATE, detection)).isEmpty();
			verifyNoInteractions(newsMatcher);
			verifyNoInteractions(narrativeService);
			verifyNoInteractions(priceMoveCardWriter);
		}

		@Test
		@DisplayName("중복 판정은 종목·거래일·종류·구간시작 네 값으로 묻는다")
		void asksTheDuplicateQuestionOnTheFullUniqueAxis() {
			PriceMoveDetectionDto detection = openingGap(LocalTime.of(9, 0));
			when(newsMatcher.match(any(), any(), any())).thenReturn(List.of(
				newsAt(LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)))));
			when(narrativeService.resolvePriceMoveNarrative(any()))
				.thenReturn(NarrativeResultDto.llm("갭 문장"));
			when(priceMoveCardWriter.persist(any(), anyList()))
				.thenAnswer(invocation -> invocation.getArgument(0));

			service.confirmStockCard(instrument, ORIGIN_TRADE_DATE, detection);

			verify(priceMoveEventRepository)
				.existsByInstrumentIdAndOriginTradeDateAndEventTypeAndWindowStart(
					INSTRUMENT_ID,
					ORIGIN_TRADE_DATE,
					PriceMoveEventType.OPENING_GAP,
					LocalTime.of(9, 0));
		}
	}

	@Nested
	@DisplayName("서술 출처와 근거 연결")
	class NarrativeAndSources {

		@Test
		@DisplayName("LLM 서술이면 narrative_source가 LLM이고 문장이 그대로 저장된다")
		void storesLlmNarrativeAndItsSource() {
			PriceMoveEvent card = confirmAndCapture(
				intraday(LocalTime.of(11, 25)),
				NarrativeResultDto.llm("반도체 업황 우려로 하락했습니다."),
				List.of(newsAt(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)))));

			assertThat(card.getNarrative()).isEqualTo("반도체 업황 우려로 하락했습니다.");
			assertThat(card.getNarrativeSource()).isEqualTo(NarrativeSource.LLM);
		}

		@Test
		@DisplayName("템플릿 폴백이면 narrative_source가 TEMPLATE이다")
		void storesTemplateNarrativeAndItsSource() {
			PriceMoveEvent card = confirmAndCapture(
				intraday(LocalTime.of(11, 25)),
				NarrativeResultDto.template("11:20~11:25에 -1.82% 움직였습니다."),
				List.of(newsAt(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)))));

			assertThat(card.getNarrativeSource()).isEqualTo(NarrativeSource.TEMPLATE);
			assertThat(card.getNarrative()).isNotBlank();
		}

		@Test
		@DisplayName("근거는 NewsMatcher가 준 순서 그대로 writer에 넘어간다")
		void passesSourcesToTheWriterInTheOrderTheMatcherReturnedThem() {
			PriceMoveDetectionDto detection = intraday(LocalTime.of(11, 25));
			List<MarketNewsItem> matched = List.of(
				newsAt(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 24))),
				newsAt(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 29))),
				newsAt(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15))));
			when(newsMatcher.match(INSTRUMENT_ID, ORIGIN_TRADE_DATE, detection)).thenReturn(matched);
			when(narrativeService.resolvePriceMoveNarrative(any()))
				.thenReturn(NarrativeResultDto.llm("장중 문장"));
			when(priceMoveCardWriter.persist(any(), anyList()))
				.thenAnswer(invocation -> invocation.getArgument(0));

			service.confirmStockCard(instrument, ORIGIN_TRADE_DATE, detection);

			@SuppressWarnings("unchecked") ArgumentCaptor<List<MarketNewsItem>> sourceCaptor = ArgumentCaptor
				.forClass(List.class);
			verify(priceMoveCardWriter).persist(any(), sourceCaptor.capture());
			assertThat(sourceCaptor.getValue()).containsExactlyElementsOf(matched);
		}

		@Test
		@DisplayName("프롬프트에 근거 기사와 시가 갭 여부가 그대로 실린다")
		void buildsPromptFromTheMatchedSources() {
			PriceMoveDetectionDto detection = openingGap(LocalTime.of(9, 3));
			confirmAndCapture(detection, NarrativeResultDto.llm("갭 문장"),
				List.of(newsAt(LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 40)))));

			ArgumentCaptor<PriceMovePromptDto> promptCaptor = ArgumentCaptor.forClass(PriceMovePromptDto.class);
			verify(narrativeService).resolvePriceMoveNarrative(promptCaptor.capture());
			PriceMovePromptDto prompt = promptCaptor.getValue();
			assertThat(prompt.openingGap()).isTrue();
			assertThat(prompt.instrumentName()).isEqualTo("삼성전자");
			assertThat(prompt.referenceDate()).isEqualTo(ORIGIN_TRADE_DATE);
			assertThat(prompt.sources()).hasSize(1);
			assertThat(prompt.sources().get(0).publishedAt())
				.isEqualTo(LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 40)));
		}

		@Test
		@DisplayName("탐지 결과의 구간·변동률·점수가 카드에 그대로 실린다")
		void copiesDetectionFieldsOntoTheCard() {
			PriceMoveEvent card = confirmAndCapture(
				intraday(LocalTime.of(11, 25)),
				NarrativeResultDto.llm("장중 문장"),
				List.of(newsAt(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)))));

			assertThat(card.getOriginTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
			assertThat(card.getWindowStart()).isEqualTo(LocalTime.of(11, 20));
			assertThat(card.getWindowEnd()).isEqualTo(LocalTime.of(11, 25));
			assertThat(card.getChangeRate()).isEqualByComparingTo("-0.018200");
			assertThat(card.getDetectionScore()).isEqualByComparingTo("3.2500");
			assertThat(card.getEventType()).isEqualTo(PriceMoveEventType.INTRADAY);
		}
	}
}
