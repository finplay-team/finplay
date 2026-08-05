// CryptoPriceMoveWatcher가 spec 012 §탐지 알고리즘(코인)의 의사코드·함정을 그대로 지키는지 검증한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.feedback.config.FeedbackDetectionProperties;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.CryptoPriceSnapshotService;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.store.PriceSnapshotDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

// 외부 협력자(스냅샷 조회·근거 매칭·서술·저장·쿨다운/일일상한 조회)를 전부 mock으로 갈아끼우고 순수 오케스트레이션과
// σ 계산만 이 클래스에서 본다(ADR-0003의 "서비스 비즈니스 로직 → 단위"). σ 표본이 실제로 겹치지 않는 구간으로
// 만들어지는지는 mock 검증만으론 부족하므로, 겹치지 않는 구성일 때만 나오는 정확한 수치를 손으로(독립적으로)
// 계산해 대조한다 — PriceMoveDetectorTest와 같은 방침이다.
//
// 기대값의 정본은 spec.md §탐지 알고리즘(코인)이고 임계치는 §C-7이다.
class CryptoPriceMoveWatcherTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 0);

	private final InstrumentService instrumentService = mock(InstrumentService.class);

	private final CryptoPriceSnapshotService cryptoPriceSnapshotService = mock(CryptoPriceSnapshotService.class);

	private final PriceMoveEventRepository priceMoveEventRepository = mock(PriceMoveEventRepository.class);

	private final PriceMoveCardWriter priceMoveCardWriter = mock(PriceMoveCardWriter.class);

	private final NewsMatcher newsMatcher = mock(NewsMatcher.class);

	private final NarrativeService narrativeService = mock(NarrativeService.class);

	private static Instrument crypto(Long id, String symbol) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, symbol, "테스트코인", BigDecimal.ONE, 5000L, true, LocalDateTime.now());
		ReflectionTestUtils.setField(instrument, "id", id);
		return instrument;
	}

	private static final Instrument INSTRUMENT = crypto(1L, "BTC");

	private static PriceSnapshotDto snapshot(LocalDateTime at, double price) {
		return new PriceSnapshotDto(at, BigDecimal.valueOf(price));
	}

	private static Clock fixedClockAt(LocalDateTime now) {
		return Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
	}

	private static FeedbackCryptoProperties properties(
		int cooldownMinutes, int dailyLimit, int rollingWindowMinutes, int sigmaLookbackHours,
		int minSampleCount, int matchBeforeMinutes) {
		return new FeedbackCryptoProperties(
			cooldownMinutes, dailyLimit, rollingWindowMinutes, sigmaLookbackHours, minSampleCount, matchBeforeMinutes);
	}

	private static FeedbackDetectionProperties detectionProperties(double zScoreK) {
		// §탐지 알고리즘(코인)은 z-score-k만 재사용한다 — 나머지 값은 이 클래스가 안 쓴다.
		return new FeedbackDetectionProperties(zScoreK, 5, 5, 2, new BigDecimal("0.01"));
	}

	private static MarketNewsItem newsItem(LocalDateTime publishedAt) {
		return MarketNewsItem.create(
			INSTRUMENT, MarketNewsItemType.NEWS, "테스트 기사", "테스트경제",
			"https://news.example.com/1", publishedAt, publishedAt);
	}

	private CryptoPriceMoveWatcher watcher(
		FeedbackCryptoProperties cryptoProperties, FeedbackDetectionProperties detectionProps, Clock clock) {
		return new CryptoPriceMoveWatcher(
			instrumentService, cryptoPriceSnapshotService, priceMoveEventRepository, priceMoveCardWriter,
			newsMatcher, narrativeService, cryptoProperties, detectionProps, clock);
	}

	// 기본 배선 — 카드 생성을 막지 않는 협력자 응답. 각 테스트가 필요한 부분만 덮어쓴다.
	private void stubNoCooldownNoLimit() {
		when(priceMoveEventRepository.findFirstByInstrumentIdAndMarketOrderByOccurredAtDesc(any(), any()))
			.thenReturn(Optional.empty());
		when(priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(any(), any(), any()))
			.thenReturn(0L);
	}

	private void stubOneMatchedSource(LocalDateTime now) {
		when(newsMatcher.matchCrypto(eq(INSTRUMENT.getId()), eq(now)))
			.thenReturn(List.of(newsItem(now.minusMinutes(5))));
		when(narrativeService.resolvePriceMoveNarrative(any()))
			.thenReturn(NarrativeResultDto.template("변동 설명"));
	}

	private void givenInstruments(Instrument... instruments) {
		when(instrumentService.getInstrumentEntities(Market.CRYPTO)).thenReturn(List.of(instruments));
	}

	// --- σ 표본 — rolling-window-minutes 간격의 "겹치지 않는" 구간으로만 만든다 (§탐지 알고리즘(코인)) ---

	@Nested
	@DisplayName("σ 표본은 겹치지 않는 구간으로만 만들어진다")
	class NonOverlappingSample {

		// sigma-lookback-hours=1·rolling-window-minutes=5이면 겹치지 않는 구간은 정확히 12개뿐이다.
		// 매 분 슬라이딩이었다면(1분 간격 스냅샷이 61개나 있어 최대 56개 표본을 만들 수 있다) min-sample-count=13도
		// 넘겼을 것이다 — 겹치지 않는 구성이라 정확히 12개에서 막혀 카드가 생기지 않는다.
		@Test
		@DisplayName("1분 간격 스냅샷이 충분해도 표본은 세그먼트 수(12개)를 넘지 못해 min-sample-count에 막힌다")
		void samplePlateausAtSegmentCountEvenWithDenseOneMinuteSnapshots() {
			List<PriceSnapshotDto> denseOneMinuteSnapshots = new ArrayList<>();
			for (int agoMinutes = 0; agoMinutes <= 60; agoMinutes++) {
				denseOneMinuteSnapshots.add(snapshot(NOW.minusMinutes(agoMinutes), 100));
			}
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any()))
				.thenReturn(denseOneMinuteSnapshots);
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();

			// minSampleCount=13 — 겹치지 않는 구간(12개)보다 딱 하나 많다. 슬라이딩이었다면(최대 56개) 통과했을 값이다.
			watcher(properties(30, 6, 5, 1, 13, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter, never()).persist(any(), any());
			verify(newsMatcher, never()).matchCrypto(any(), any());
		}

		// 겹치지 않는 구성일 때만 나오는 정확한 수치를 손으로 다시 계산해 대조한다(스크래치패드 시뮬레이터, 값은
		// CryptoPriceMoveWatcherTest 클래스 주석의 방침과 같다). 매 분 슬라이딩으로 계산했다면 detectionScore가
		// 3.4754가 되어(56개 표본, 그중 5개가 0.12) 이 단정과 달라진다 — 반대 값도 함께 확인해 어긋남을 못박는다.
		@Test
		@DisplayName("겹치지 않는 구성으로 계산한 정확한 σ·changeRate·detectionScore로 카드를 만든다")
		void computesExactNonOverlappingSigmaAndScore() {
			double p = 100.0;
			double q = 100.0 * Math.exp(0.12);
			// ago 0~4분은 점프 이후(q), ago 5~60분은 점프 이전(p) — 점프는 ago 4·5 사이 단 한 번이다.
			List<PriceSnapshotDto> denseOneMinuteSnapshots = new ArrayList<>();
			for (int agoMinutes = 0; agoMinutes <= 60; agoMinutes++) {
				denseOneMinuteSnapshots.add(snapshot(NOW.minusMinutes(agoMinutes), agoMinutes < 5 ? q : p));
			}
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any()))
				.thenReturn(denseOneMinuteSnapshots);
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();
			stubOneMatchedSource(NOW);

			// 겹치지 않는 12개 구간(각 5분): i=0(ago0~5)만 ln(q/p)=0.12, 나머지 11개는 0이다.
			// mean=0.01, Σ(x-mean)^2=11*0.01^2+0.11^2=0.0132, σ=sqrt(0.0132/11)=0.034641016...
			// score=0.12/σ=3.464101615... → scale 4 HALF_UP = 3.4641
			// changeRate=expm1(0.12)=0.127496851... → scale 6 HALF_UP = 0.127497
			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			ArgumentCaptor<PriceMoveEvent> captor = ArgumentCaptor.forClass(PriceMoveEvent.class);
			verify(priceMoveCardWriter).persist(captor.capture(), any());
			PriceMoveEvent card = captor.getValue();
			assertThat(card.getChangeRate()).isEqualByComparingTo("0.127497");
			assertThat(card.getDetectionScore()).isEqualByComparingTo("3.4641");
			// 슬라이딩이었다면 나왔을 값과 다르다는 것도 함께 못박는다 — 우연히 같은 값이 나와 이 단정이
			// 무력화되지 않게 한다.
			assertThat(card.getDetectionScore()).isNotEqualByComparingTo("3.4754");
		}

		// 각 구간도 양 끝 스냅샷이 있을 때만 표본으로 만든다 — 한쪽이라도 없으면 그 구간은 건너뛴다.
		@Test
		@DisplayName("구간 한쪽 끝 스냅샷이 없으면 그 구간은 표본에서 빠진다")
		void skipsSegmentsMissingEitherEndpoint() {
			// ago 0·5(최근 구간)만 있고 나머지 세그먼트 경계(10,15,...,60)는 전부 없다 — 표본은 1개뿐이다.
			List<PriceSnapshotDto> sparse = List.of(snapshot(NOW, 110), snapshot(NOW.minusMinutes(5), 100));
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(sparse);
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();

			watcher(properties(30, 6, 5, 1, 2, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter, never()).persist(any(), any());
		}
	}

	// --- lookback 경계 — 조회 창을 [now - sigma-lookback-hours, now]로 정확히 요청한다 ---

	@Nested
	@DisplayName("lookback 경계")
	class Lookback {

		@Test
		@DisplayName("스냅샷 조회 창은 now - sigma-lookback-hours부터 now까지다")
		void requestsSnapshotsForExactlyTheSigmaLookbackWindow() {
			when(cryptoPriceSnapshotService.getSnapshots(any(), any(), any())).thenReturn(List.of());
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();

			watcher(properties(30, 6, 5, 24, 100, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(cryptoPriceSnapshotService).getSnapshots("BTC", NOW.minusHours(24), NOW);
		}
	}

	// --- 표본 부족(기동 직후)과 σ=0(가격 불변) — 둘 다 조용히 종료하지만 원인이 다르다 ---

	@Nested
	@DisplayName("표본 부족과 σ=0을 구분한다")
	class InsufficientSampleVsZeroSigma {

		@Test
		@DisplayName("표본이 min-sample-count 미만이면(기동 직후) 예외 없이 카드를 만들지 않는다")
		void skipsSilentlyWhenSampleCountIsBelowMinimum() {
			// ago 0,5,10,15만 있고 그 뒤(20~60)는 없다 — 겹치지 않는 구간 3개(0-5,5-10,10-15)만 만들어진다.
			List<PriceSnapshotDto> partial = List.of(
				snapshot(NOW, 110),
				snapshot(NOW.minusMinutes(5), 105),
				snapshot(NOW.minusMinutes(10), 103),
				snapshot(NOW.minusMinutes(15), 100));
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(partial);
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();

			// minSampleCount=12인데 표본은 3개뿐이다.
			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter, never()).persist(any(), any());
			verify(newsMatcher, never()).matchCrypto(any(), any());
		}

		@Test
		@DisplayName("가격이 전혀 변하지 않으면(σ=0) 표본이 충족돼도 예외 없이 카드를 만들지 않는다")
		void skipsSilentlyWhenSigmaIsZeroEvenWithEnoughSamples() {
			List<PriceSnapshotDto> flat = new ArrayList<>();
			for (int i = 0; i <= 12; i++) {
				flat.add(snapshot(NOW.minusMinutes(i * 5L), 100));
			}
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(flat);
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();

			// minSampleCount=12 — 표본 12개를 정확히 채우지만 전부 동일가라 σ=0이다.
			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter, never()).persist(any(), any());
			verify(newsMatcher, never()).matchCrypto(any(), any());
		}
	}

	// --- |r5| / σ24 < k 종료 ---

	@Nested
	@DisplayName("z-score-k 미달 종료")
	class ZScoreThreshold {

		private List<PriceSnapshotDto> jumpFixture(double p, double q) {
			List<PriceSnapshotDto> snapshots = new ArrayList<>();
			for (int agoMinutes = 0; agoMinutes <= 60; agoMinutes++) {
				snapshots.add(snapshot(NOW.minusMinutes(agoMinutes), agoMinutes < 5 ? q : p));
			}
			return snapshots;
		}

		@Test
		@DisplayName("score가 z-score-k 미만이면 카드를 만들지 않는다")
		void skipsWhenScoreIsBelowK() {
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any()))
				.thenReturn(jumpFixture(100.0, 100.0 * Math.exp(0.12)));
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();

			// 이 픽스처의 score는 약 3.4641이다(위 계산 참고). k=10으로 두면 항상 미달이다.
			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(10.0), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter, never()).persist(any(), any());
		}

		@Test
		@DisplayName("같은 score라도 z-score-k가 그보다 낮으면 카드를 만든다")
		void createsCardWhenScoreExceedsK() {
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any()))
				.thenReturn(jumpFixture(100.0, 100.0 * Math.exp(0.12)));
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();
			stubOneMatchedSource(NOW);

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter).persist(any(), any());
		}
	}

	// --- 쿨다운 ---

	@Nested
	@DisplayName("쿨다운")
	class Cooldown {

		private void givenSufficientMove() {
			List<PriceSnapshotDto> fixture = new ArrayList<>();
			for (int agoMinutes = 0; agoMinutes <= 60; agoMinutes++) {
				fixture.add(snapshot(NOW.minusMinutes(agoMinutes), agoMinutes < 5 ? 100.0 * Math.exp(0.12) : 100.0));
			}
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(fixture);
			givenInstruments(INSTRUMENT);
		}

		@Test
		@DisplayName("마지막 카드 생성 후 cooldown-minutes 이내면 강한 신호에도 카드를 만들지 않는다")
		void skipsWithinCooldownEvenWithAStrongSignal() {
			givenSufficientMove();
			stubOneMatchedSource(NOW);
			when(priceMoveEventRepository.findFirstByInstrumentIdAndMarketOrderByOccurredAtDesc(
				INSTRUMENT.getId(), Market.CRYPTO))
				.thenReturn(Optional.of(PriceMoveEvent.createCrypto(
					INSTRUMENT, NOW.minusMinutes(29), BigDecimal.ZERO, BigDecimal.ONE, "이전 카드",
					NarrativeSource.TEMPLATE, NOW)));
			when(priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(any(), any(), any()))
				.thenReturn(0L);

			// cooldown-minutes=30인데 마지막 카드가 29분 전이다 — 쿨다운 이내다.
			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter, never()).persist(any(), any());
			verify(newsMatcher, never()).matchCrypto(any(), any());
		}

		@Test
		@DisplayName("마지막 카드 생성 후 정확히 cooldown-minutes가 지나면 카드를 만든다 — 경계는 포함")
		void createsCardExactlyAtTheCooldownBoundary() {
			givenSufficientMove();
			stubOneMatchedSource(NOW);
			when(priceMoveEventRepository.findFirstByInstrumentIdAndMarketOrderByOccurredAtDesc(
				INSTRUMENT.getId(), Market.CRYPTO))
				.thenReturn(Optional.of(PriceMoveEvent.createCrypto(
					INSTRUMENT, NOW.minusMinutes(30), BigDecimal.ZERO, BigDecimal.ONE, "이전 카드",
					NarrativeSource.TEMPLATE, NOW)));
			when(priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(any(), any(), any()))
				.thenReturn(0L);

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter).persist(any(), any());
		}
	}

	// --- 일일 상한 ---

	@Nested
	@DisplayName("일일 상한")
	class DailyLimit {

		private void givenSufficientMoveWithoutCooldown() {
			List<PriceSnapshotDto> fixture = new ArrayList<>();
			for (int agoMinutes = 0; agoMinutes <= 60; agoMinutes++) {
				fixture.add(snapshot(NOW.minusMinutes(agoMinutes), agoMinutes < 5 ? 100.0 * Math.exp(0.12) : 100.0));
			}
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(fixture);
			givenInstruments(INSTRUMENT);
			when(priceMoveEventRepository.findFirstByInstrumentIdAndMarketOrderByOccurredAtDesc(any(), any()))
				.thenReturn(Optional.empty());
		}

		@Test
		@DisplayName("오늘 생성 건수가 daily-limit에 도달하면 카드를 만들지 않는다")
		void skipsWhenDailyLimitIsReached() {
			givenSufficientMoveWithoutCooldown();
			stubOneMatchedSource(NOW);
			when(priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(
				INSTRUMENT.getId(), Market.CRYPTO, NOW.toLocalDate())).thenReturn(6L);

			// daily-limit=6이고 오늘 이미 6건이다.
			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter, never()).persist(any(), any());
			verify(newsMatcher, never()).matchCrypto(any(), any());
		}

		@Test
		@DisplayName("오늘 생성 건수가 daily-limit보다 하나 적으면 카드를 만든다")
		void createsCardWhenOneBelowDailyLimit() {
			givenSufficientMoveWithoutCooldown();
			stubOneMatchedSource(NOW);
			when(priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(
				INSTRUMENT.getId(), Market.CRYPTO, NOW.toLocalDate())).thenReturn(5L);

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter).persist(any(), any());
		}
	}

	// --- 근거 매칭 0건이면 카드 미생성 ---

	@Nested
	@DisplayName("근거 매칭")
	class Evidence {

		@Test
		@DisplayName("근거 기사가 0건이면 서술 생성·저장 없이 카드를 만들지 않는다")
		void skipsWhenNoEvidenceIsMatched() {
			List<PriceSnapshotDto> fixture = new ArrayList<>();
			for (int agoMinutes = 0; agoMinutes <= 60; agoMinutes++) {
				fixture.add(snapshot(NOW.minusMinutes(agoMinutes), agoMinutes < 5 ? 100.0 * Math.exp(0.12) : 100.0));
			}
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(fixture);
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();
			when(newsMatcher.matchCrypto(INSTRUMENT.getId(), NOW)).thenReturn(List.of());

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(narrativeService, never()).resolvePriceMoveNarrative(any());
			verify(priceMoveCardWriter, never()).persist(any(), any());
		}

		@Test
		@DisplayName("근거 기사가 있으면 그 목록 그대로 카드와 함께 저장한다")
		void persistsTheMatchedSourcesAsIsWhenEvidenceExists() {
			List<PriceSnapshotDto> fixture = new ArrayList<>();
			for (int agoMinutes = 0; agoMinutes <= 60; agoMinutes++) {
				fixture.add(snapshot(NOW.minusMinutes(agoMinutes), agoMinutes < 5 ? 100.0 * Math.exp(0.12) : 100.0));
			}
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(fixture);
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();
			List<MarketNewsItem> matched = List.of(newsItem(NOW.minusMinutes(3)), newsItem(NOW.minusMinutes(10)));
			when(newsMatcher.matchCrypto(INSTRUMENT.getId(), NOW)).thenReturn(matched);
			when(narrativeService.resolvePriceMoveNarrative(any())).thenReturn(NarrativeResultDto.llm("변동 설명"));

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter).persist(any(), eq(matched));
		}
	}

	// --- 종목 하나 실패해도 나머지는 계속한다 ---

	@Nested
	@DisplayName("종목별 실패 격리")
	class FailureIsolation {

		@Test
		@DisplayName("한 종목의 스냅샷 조회가 예외를 던져도 나머지 종목은 계속 감시한다")
		void continuesWithOtherInstrumentsWhenOneThrows() {
			Instrument failing = crypto(2L, "ETH");
			Instrument healthy = crypto(3L, "XRP");
			givenInstruments(failing, healthy);
			stubNoCooldownNoLimit();
			when(cryptoPriceSnapshotService.getSnapshots(eq("ETH"), any(), any()))
				.thenThrow(new IllegalStateException("Redis 장애"));
			when(cryptoPriceSnapshotService.getSnapshots(eq("XRP"), any(), any())).thenReturn(List.of());

			org.assertj.core.api.Assertions.assertThatCode(
				() -> watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch())
				.doesNotThrowAnyException();

			verify(cryptoPriceSnapshotService).getSnapshots(eq("XRP"), any(), any());
		}
	}

	// --- 함정 — 자정을 넘긴 카드 (§C-9) ---

	@Nested
	@DisplayName("자정을 넘긴 카드")
	class MidnightCrossing {

		private static final LocalDateTime JUST_AFTER_MIDNIGHT = LocalDateTime.of(2026, 8, 4, 0, 3);

		@Test
		@DisplayName("occurred_at 00:03 픽스처에서도 origin_trade_date가 그날(00:03의) KST 날짜로 저장된다")
		void storesOriginTradeDateAsTheKstDateOfTheOccurredAtTimestamp() {
			List<PriceSnapshotDto> fixture = new ArrayList<>();
			for (int agoMinutes = 0; agoMinutes <= 60; agoMinutes++) {
				fixture.add(snapshot(
					JUST_AFTER_MIDNIGHT.minusMinutes(agoMinutes),
					agoMinutes < 5 ? 100.0 * Math.exp(0.12) : 100.0));
			}
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(fixture);
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();
			stubOneMatchedSource(JUST_AFTER_MIDNIGHT);

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(JUST_AFTER_MIDNIGHT))
				.watch();

			ArgumentCaptor<PriceMoveEvent> captor = ArgumentCaptor.forClass(PriceMoveEvent.class);
			verify(priceMoveCardWriter).persist(captor.capture(), any());
			PriceMoveEvent card = captor.getValue();
			assertThat(card.getOccurredAt()).isEqualTo(JUST_AFTER_MIDNIGHT);
			// 구간이 시작된 전날(8/3)이 아니라 탐지 시각의 날짜(8/4)다 — 자정 직후 카드가 전날 몫으로 새지 않는다.
			assertThat(card.getOriginTradeDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 4));
			assertThat(card.getOriginTradeDate()).isNotEqualTo(java.time.LocalDate.of(2026, 8, 3));
		}

		// lookback 시작(now - sigma-lookback-hours)이 전날로 넘어가도 windowStart <= windowEnd가 그대로
		// 유지되는지 — LocalDateTime 뺄셈은 자정과 무관하게 항상 시간 순서를 지킨다. 여기서는 실제로 넘어간
		// 값(전날 23시)이 그대로 협력자에 전달되는지를 못박아 회귀를 잡는다.
		@Test
		@DisplayName("lookback 시작이 전날로 넘어가도 now보다 항상 앞선 시각으로 정확히 전달된다")
		void lookbackStartCrossesMidnightButStaysBeforeNow() {
			when(cryptoPriceSnapshotService.getSnapshots(any(), any(), any())).thenReturn(List.of());
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(JUST_AFTER_MIDNIGHT))
				.watch();

			LocalDateTime expectedLookbackStart = LocalDateTime.of(2026, 8, 3, 23, 3);
			verify(cryptoPriceSnapshotService).getSnapshots("BTC", expectedLookbackStart, JUST_AFTER_MIDNIGHT);
			assertThat(expectedLookbackStart).isBefore(JUST_AFTER_MIDNIGHT);
		}

		// windowStart(LocalTime)가 windowEnd(LocalTime)보다 커지는(23:58 > 00:03) 상황에서도 프롬프트의
		// windowMinutes는 별도로 넘겨 구간 길이를 다시 계산하지 않는다는 설계를 지킨다(PriceMovePromptDto 주석).
		@Test
		@DisplayName("자정을 넘겨도 프롬프트의 windowMinutes가 절대 시각 차가 아니라 설정값 그대로 전달된다")
		void promptCarriesConfiguredWindowMinutesAcrossMidnight() {
			List<PriceSnapshotDto> fixture = new ArrayList<>();
			for (int agoMinutes = 0; agoMinutes <= 60; agoMinutes++) {
				fixture.add(snapshot(
					JUST_AFTER_MIDNIGHT.minusMinutes(agoMinutes),
					agoMinutes < 5 ? 100.0 * Math.exp(0.12) : 100.0));
			}
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(fixture);
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();
			stubOneMatchedSource(JUST_AFTER_MIDNIGHT);

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(JUST_AFTER_MIDNIGHT))
				.watch();

			ArgumentCaptor<PriceMovePromptDto> captor = ArgumentCaptor.forClass(PriceMovePromptDto.class);
			verify(narrativeService).resolvePriceMoveNarrative(captor.capture());
			PriceMovePromptDto prompt = captor.getValue();
			assertThat(prompt.windowMinutes()).isEqualTo(5);
			assertThat(prompt.windowEnd()).isEqualTo(LocalTime.of(0, 3));
			assertThat(prompt.windowStart()).isEqualTo(LocalTime.of(23, 58));
			assertThat(prompt.referenceDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 4));
		}
	}

	// changeRate·detectionScore의 스케일이 실제로 컬럼 정의(§C-8)와 같은지 — 반올림 규칙이 다르면 저장 단계에서
	// 값이 조용히 달라진다.
	@Test
	@DisplayName("저장되는 changeRate·detectionScore는 각각 scale 6·4로 HALF_UP 반올림된다")
	void roundsChangeRateAndDetectionScoreToTheirColumnScales() {
		List<PriceSnapshotDto> fixture = new ArrayList<>();
		for (int agoMinutes = 0; agoMinutes <= 60; agoMinutes++) {
			fixture.add(snapshot(NOW.minusMinutes(agoMinutes), agoMinutes < 5 ? 100.0 * Math.exp(0.12) : 100.0));
		}
		when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(fixture);
		givenInstruments(INSTRUMENT);
		stubNoCooldownNoLimit();
		stubOneMatchedSource(NOW);

		watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

		ArgumentCaptor<PriceMoveEvent> captor = ArgumentCaptor.forClass(PriceMoveEvent.class);
		verify(priceMoveCardWriter).persist(captor.capture(), any());
		PriceMoveEvent card = captor.getValue();
		assertThat(card.getChangeRate().scale()).isEqualTo(6);
		assertThat(card.getDetectionScore().scale()).isEqualTo(4);
		assertThat(card.getChangeRate().setScale(6, RoundingMode.HALF_UP)).isEqualTo(card.getChangeRate());
	}
}
