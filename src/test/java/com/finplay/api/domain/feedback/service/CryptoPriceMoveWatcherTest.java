package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.domain.feedback.config.FeedbackDetectionProperties;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.CryptoPriceSnapshotService;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.store.PriceSnapshotDto;
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

class CryptoPriceMoveWatcherTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 0);

	private static final int IRRELEVANT_WATCH_LOCK_TTL_SECONDS = 30;

	private final InstrumentService instrumentService = mock(InstrumentService.class);

	private final CryptoPriceSnapshotService cryptoPriceSnapshotService = mock(CryptoPriceSnapshotService.class);

	private final PriceMoveEventRepository priceMoveEventRepository = mock(PriceMoveEventRepository.class);

	private final PriceMoveCardWriter priceMoveCardWriter = mock(PriceMoveCardWriter.class);

	private final NewsMatcher newsMatcher = mock(NewsMatcher.class);

	private final NewsCollectionService newsCollectionService = mock(NewsCollectionService.class);

	private final NarrativeService narrativeService = mock(NarrativeService.class);

	private final CryptoWatchLock cryptoWatchLock = alwaysSucceedingLock();

	private static CryptoWatchLock alwaysSucceedingLock() {
		CryptoWatchLock lock = mock(CryptoWatchLock.class);
		when(lock.tryLock(any())).thenReturn(Optional.of("test-lock-token"));
		return lock;
	}

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
			cooldownMinutes, dailyLimit, rollingWindowMinutes, sigmaLookbackHours, minSampleCount, matchBeforeMinutes,
			IRRELEVANT_WATCH_LOCK_TTL_SECONDS);
	}

	private static FeedbackDetectionProperties detectionProperties(double zScoreK) {
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
			cryptoWatchLock, newsMatcher, newsCollectionService, narrativeService,
			cryptoProperties, detectionProps, clock);
	}

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
		when(instrumentService.getRealInstrumentEntities(Market.CRYPTO)).thenReturn(List.of(instruments));
	}


	@Nested
	@DisplayName("σ 표본은 겹치지 않는 구간으로만 만들어진다")
	class NonOverlappingSample {

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

			watcher(properties(30, 6, 5, 1, 13, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter, never()).persist(any(), any());
			verify(newsMatcher, never()).matchCrypto(any(), any());
		}

		@Test
		@DisplayName("겹치지 않는 구성으로 계산한 정확한 σ·changeRate·detectionScore로 카드를 만든다")
		void computesExactNonOverlappingSigmaAndScore() {
			double p = 100.0;
			double q = 100.0 * Math.exp(0.12);
			List<PriceSnapshotDto> denseOneMinuteSnapshots = new ArrayList<>();
			for (int agoMinutes = 0; agoMinutes <= 60; agoMinutes++) {
				denseOneMinuteSnapshots.add(snapshot(NOW.minusMinutes(agoMinutes), agoMinutes < 5 ? q : p));
			}
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any()))
				.thenReturn(denseOneMinuteSnapshots);
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();
			stubOneMatchedSource(NOW);

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			ArgumentCaptor<PriceMoveEvent> captor = ArgumentCaptor.forClass(PriceMoveEvent.class);
			verify(priceMoveCardWriter).persist(captor.capture(), any());
			PriceMoveEvent card = captor.getValue();
			assertThat(card.getChangeRate()).isEqualByComparingTo("0.127497");
			assertThat(card.getDetectionScore()).isEqualByComparingTo("3.4641");
			assertThat(card.getDetectionScore()).isNotEqualByComparingTo("3.4754");
		}

		@Test
		@DisplayName("구간 한쪽 끝 스냅샷이 없으면 그 구간은 표본에서 빠진다")
		void skipsSegmentsMissingEitherEndpoint() {
			List<PriceSnapshotDto> sparse = List.of(snapshot(NOW, 110), snapshot(NOW.minusMinutes(5), 100));
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(sparse);
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();

			watcher(properties(30, 6, 5, 1, 2, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter, never()).persist(any(), any());
		}
	}


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


	@Nested
	@DisplayName("표본 부족과 σ=0을 구분한다")
	class InsufficientSampleVsZeroSigma {

		@Test
		@DisplayName("표본이 min-sample-count 미만이면(기동 직후) 예외 없이 카드를 만들지 않는다")
		void skipsSilentlyWhenSampleCountIsBelowMinimum() {
			List<PriceSnapshotDto> partial = List.of(
				snapshot(NOW, 110),
				snapshot(NOW.minusMinutes(5), 105),
				snapshot(NOW.minusMinutes(10), 103),
				snapshot(NOW.minusMinutes(15), 100));
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(partial);
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();

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

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter, never()).persist(any(), any());
			verify(newsMatcher, never()).matchCrypto(any(), any());
		}
	}


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

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(10.0), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter, never()).persist(any(), any());
			verify(newsCollectionService, never()).collectForInstrument(any());
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

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter, never()).persist(any(), any());
			verify(newsMatcher, never()).matchCrypto(any(), any());
			verify(newsCollectionService, never()).collectForInstrument(any());
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

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter, never()).persist(any(), any());
			verify(newsMatcher, never()).matchCrypto(any(), any());
			verify(newsCollectionService, never()).collectForInstrument(any());
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
			verify(newsCollectionService, never()).collectForInstrument(any());
		}
	}


	@Nested
	@DisplayName("온디맨드 수집")
	class OnDemandCollection {

		private List<PriceSnapshotDto> jumpFixture() {
			List<PriceSnapshotDto> fixture = new ArrayList<>();
			for (int agoMinutes = 0; agoMinutes <= 60; agoMinutes++) {
				fixture.add(snapshot(NOW.minusMinutes(agoMinutes), agoMinutes < 5 ? 100.0 * Math.exp(0.12) : 100.0));
			}
			return fixture;
		}

		@Test
		@DisplayName("첫 매칭이 비면 온디맨드 수집 후 재매칭해 근거가 생기면 카드를 만든다")
		void collectsOnDemandAndRetriesMatchWhenFirstMatchIsEmpty() {
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(jumpFixture());
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();
			List<MarketNewsItem> matched = List.of(newsItem(NOW.minusMinutes(3)));
			when(newsMatcher.matchCrypto(INSTRUMENT.getId(), NOW))
				.thenReturn(List.of())
				.thenReturn(matched);
			when(narrativeService.resolvePriceMoveNarrative(any())).thenReturn(NarrativeResultDto.llm("변동 설명"));

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(newsCollectionService).collectForInstrument(INSTRUMENT);
			verify(priceMoveCardWriter).persist(any(), eq(matched));
		}

		@Test
		@DisplayName("재매칭도 비면 카드를 만들지 않고, 수집·재매칭은 정확히 1회씩만 일어난다")
		void skipsCardWhenRetryStillEmptyAndCollectsOnlyOnce() {
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(jumpFixture());
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();
			when(newsMatcher.matchCrypto(INSTRUMENT.getId(), NOW)).thenReturn(List.of());

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(newsCollectionService, times(1)).collectForInstrument(any());
			verify(newsMatcher, times(2)).matchCrypto(any(), any());
			verify(priceMoveCardWriter, never()).persist(any(), any());
		}
	}


	@Nested
	@DisplayName("코인 감시 락 획득 실패")
	class WatchLockAcquisitionFailure {

		@Test
		@DisplayName("락 획득에 실패하면 쿨다운·일일상한 조회부터 근거 매칭·서술·저장까지 전부 건너뛴다")
		void skipsCooldownEvidenceNarrativeAndPersistWhenLockAcquisitionFails() {
			List<PriceSnapshotDto> fixture = new ArrayList<>();
			for (int agoMinutes = 0; agoMinutes <= 60; agoMinutes++) {
				fixture.add(snapshot(NOW.minusMinutes(agoMinutes), agoMinutes < 5 ? 100.0 * Math.exp(0.12) : 100.0));
			}
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(fixture);
			givenInstruments(INSTRUMENT);
			when(cryptoWatchLock.tryLock(INSTRUMENT.getId())).thenReturn(Optional.empty());

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveEventRepository, never())
				.findFirstByInstrumentIdAndMarketOrderByOccurredAtDesc(any(), any());
			verify(priceMoveEventRepository, never())
				.countByInstrumentIdAndMarketAndOriginTradeDate(any(), any(), any());
			verify(newsMatcher, never()).matchCrypto(any(), any());
			verify(newsCollectionService, never()).collectForInstrument(any());
			verify(narrativeService, never()).resolvePriceMoveNarrative(any());
			verify(priceMoveCardWriter, never()).persist(any(), any());
			verify(cryptoWatchLock, never()).unlock(any(), any());
		}
	}


	@Nested
	@DisplayName("락 해제 보장")
	class LockReleaseGuarantee {

		private List<PriceSnapshotDto> jumpFixture() {
			List<PriceSnapshotDto> fixture = new ArrayList<>();
			for (int agoMinutes = 0; agoMinutes <= 60; agoMinutes++) {
				fixture.add(snapshot(NOW.minusMinutes(agoMinutes), agoMinutes < 5 ? 100.0 * Math.exp(0.12) : 100.0));
			}
			return fixture;
		}

		@Test
		@DisplayName("카드를 정상 생성한 뒤 tryLock이 돌려준 토큰 그대로 unlock을 호출한다")
		void unlocksWithTheTokenReturnedByTryLockAfterPersistingACard() {
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(jumpFixture());
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();
			stubOneMatchedSource(NOW);

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch();

			verify(priceMoveCardWriter).persist(any(), any());
			verify(cryptoWatchLock).unlock(INSTRUMENT.getId(), "test-lock-token");
		}

		@Test
		@DisplayName("근거 매칭이 예외를 던져도 finally에서 unlock이 호출된다")
		void unlocksEvenWhenNewsMatcherThrowsAfterLockIsAcquired() {
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(jumpFixture());
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();
			when(newsMatcher.matchCrypto(INSTRUMENT.getId(), NOW))
				.thenThrow(new IllegalStateException("근거 매칭 중 장애"));

			org.assertj.core.api.Assertions.assertThatCode(
				() -> watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch())
				.doesNotThrowAnyException();

			verify(priceMoveCardWriter, never()).persist(any(), any());
			verify(cryptoWatchLock).unlock(INSTRUMENT.getId(), "test-lock-token");
		}

		@Test
		@DisplayName("온디맨드 수집이 예외를 던져도 finally에서 unlock이 호출된다")
		void unlocksEvenWhenOnDemandCollectionThrows() {
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(jumpFixture());
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();
			when(newsMatcher.matchCrypto(INSTRUMENT.getId(), NOW)).thenReturn(List.of());
			when(newsCollectionService.collectForInstrument(INSTRUMENT))
				.thenThrow(new RuntimeException("네이버 API 장애"));

			org.assertj.core.api.Assertions.assertThatCode(
				() -> watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(NOW)).watch())
				.doesNotThrowAnyException();

			verify(priceMoveCardWriter, never()).persist(any(), any());
			verify(cryptoWatchLock).unlock(INSTRUMENT.getId(), "test-lock-token");
		}
	}


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
			assertThat(card.getOriginTradeDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 4));
			assertThat(card.getOriginTradeDate()).isNotEqualTo(java.time.LocalDate.of(2026, 8, 3));
		}

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

	@Nested
	@DisplayName("카드 시각의 분 경계 정렬")
	class OccurredAtMinuteBoundary {

		private static final LocalDateTime CRON_NOW = LocalDateTime.of(2026, 8, 5, 10, 0, 30, 123_456_000);

		private List<PriceSnapshotDto> jumpFixture() {
			List<PriceSnapshotDto> fixture = new ArrayList<>();
			for (int agoMinutes = 0; agoMinutes <= 60; agoMinutes++) {
				fixture.add(
					snapshot(CRON_NOW.minusMinutes(agoMinutes), agoMinutes < 5 ? 100.0 * Math.exp(0.12) : 100.0));
			}
			return fixture;
		}

		@Test
		@DisplayName("occurredAt은 분 경계로 내려 저장하고, createdAt은 실제 탐지 시각을 그대로 남긴다")
		void storesOccurredAtOnTheMinuteBoundaryButKeepsTheRealDetectionInstant() {
			when(cryptoPriceSnapshotService.getSnapshots(eq("BTC"), any(), any())).thenReturn(jumpFixture());
			givenInstruments(INSTRUMENT);
			stubNoCooldownNoLimit();
			stubOneMatchedSource(CRON_NOW);

			watcher(properties(30, 6, 5, 1, 12, 35), detectionProperties(2.5), fixedClockAt(CRON_NOW)).watch();

			ArgumentCaptor<PriceMoveEvent> captor = ArgumentCaptor.forClass(PriceMoveEvent.class);
			verify(priceMoveCardWriter).persist(captor.capture(), any());
			PriceMoveEvent card = captor.getValue();
			assertThat(card.getOccurredAt()).isEqualTo(LocalDateTime.of(2026, 8, 5, 10, 0, 0));
			assertThat(card.getCreatedAt()).isEqualTo(CRON_NOW);
		}
	}
}
