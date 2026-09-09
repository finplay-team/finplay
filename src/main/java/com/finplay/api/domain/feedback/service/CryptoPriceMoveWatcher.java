package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.domain.feedback.config.FeedbackDetectionProperties;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoPriceMoveWatcher {

	private static final int CHANGE_RATE_SCALE = 6;

	private static final int DETECTION_SCORE_SCALE = 4;

	private static final Duration SNAPSHOT_TOLERANCE = Duration.ofMinutes(1);

	private final InstrumentService instrumentService;

	private final CryptoPriceSnapshotService cryptoPriceSnapshotService;

	private final PriceMoveEventRepository priceMoveEventRepository;

	private final PriceMoveCardWriter priceMoveCardWriter;

	private final CryptoWatchLock cryptoWatchLock;

	private final NewsMatcher newsMatcher;

	private final NewsCollectionService newsCollectionService;

	private final NarrativeService narrativeService;

	private final FeedbackCryptoProperties cryptoProperties;

	private final FeedbackDetectionProperties detectionProperties;

	private final Clock clock;

	@Scheduled(cron = "${feedback.batch.crypto-watch-cron}", zone = "Asia/Seoul")
	public void watch() {
		LocalDateTime now = LocalDateTime.now(clock);
		List<Instrument> instruments = instrumentService.getRealInstrumentEntities(Market.CRYPTO);
		int created = 0;
		for (Instrument instrument : instruments) {
			try {
				if (watchOne(instrument, now)) {
					created++;
				}
			} catch (RuntimeException ex) {
				log.warn("코인 변동 감시 중 종목 하나가 실패해 건너뛴다. 종목={}", instrument.getId(), ex);
			}
		}
		log.debug("코인 변동 감시를 마쳤다. 생성된 카드={}건", created);
	}

	private boolean watchOne(Instrument instrument, LocalDateTime now) {
		int rollingWindowMinutes = cryptoProperties.rollingWindowMinutes();
		LocalDateTime lookbackStart = now.minusHours(cryptoProperties.sigmaLookbackHours());
		NavigableMap<LocalDateTime, PriceSnapshotDto> byTime = indexByTime(
			cryptoPriceSnapshotService.getSnapshots(instrument.getSymbol(), lookbackStart, now));

		Optional<PriceSnapshotDto> pNow = nearest(byTime, now);
		Optional<PriceSnapshotDto> pPast = nearest(byTime, now.minusMinutes(rollingWindowMinutes));
		if (pNow.isEmpty() || pPast.isEmpty()) {
			return false;
		}

		List<Double> sample = buildNonOverlappingLogReturns(byTime, now, rollingWindowMinutes);
		if (sample.size() < cryptoProperties.minSampleCount()) {
			return false;
		}
		double sigma24 = sampleStandardDeviation(sample);
		if (sigma24 == 0) {
			return false;
		}

		double r5 = Math.log(pNow.get().price().doubleValue() / pPast.get().price().doubleValue());
		double score = Math.abs(r5) / sigma24;
		if (score < detectionProperties.zScoreK()) {
			return false;
		}

		Optional<String> lockToken = cryptoWatchLock.tryLock(instrument.getId());
		if (lockToken.isEmpty()) {
			log.debug(
				"코인 감시 락을 얻지 못해 이번 틱을 건너뛴다(다른 인스턴스가 처리 중이거나 Redis 문제로 "
					+ "락을 얻지 못함). 종목={}",
				instrument.getId());
			return false;
		}
		try {
			if (isWithinCooldown(instrument.getId(), now) || reachedDailyLimit(instrument.getId(), now)) {
				return false;
			}

			List<MarketNewsItem> sources = newsMatcher.matchCrypto(instrument.getId(), now);
			if (sources.isEmpty()) {
				newsCollectionService.collectForInstrument(instrument);
				sources = newsMatcher.matchCrypto(instrument.getId(), now);
				if (sources.isEmpty()) {
					return false;
				}
			}

			BigDecimal changeRate = scaled(Math.expm1(r5), CHANGE_RATE_SCALE);
			BigDecimal detectionScore = scaled(score, DETECTION_SCORE_SCALE);
			NarrativeResultDto narrative = narrativeService.resolvePriceMoveNarrative(
				toPrompt(instrument, now, rollingWindowMinutes, changeRate, sources));
			LocalDateTime occurredAt = PostSellArithmetic.onMinuteBoundary(now);
			PriceMoveEvent card = PriceMoveEvent.createCrypto(
				instrument, occurredAt, changeRate, detectionScore, narrative.narrative(), narrative.source(), now);
			priceMoveCardWriter.persist(card, sources);
			return true;
		} finally {
			cryptoWatchLock.unlock(instrument.getId(), lockToken.get());
		}
	}

	private boolean isWithinCooldown(Long instrumentId, LocalDateTime now) {
		return priceMoveEventRepository
			.findFirstByInstrumentIdAndMarketOrderByOccurredAtDesc(instrumentId, Market.CRYPTO)
			.map(PriceMoveEvent::getOccurredAt)
			.map(lastOccurredAt -> now.isBefore(lastOccurredAt.plusMinutes(cryptoProperties.cooldownMinutes())))
			.orElse(false);
	}

	private boolean reachedDailyLimit(Long instrumentId, LocalDateTime now) {
		long todayCount = priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(
			instrumentId, Market.CRYPTO, now.toLocalDate());
		return todayCount >= cryptoProperties.dailyLimit();
	}

	private List<Double> buildNonOverlappingLogReturns(
		NavigableMap<LocalDateTime, PriceSnapshotDto> byTime, LocalDateTime now, int windowMinutes) {
		int segments = (cryptoProperties.sigmaLookbackHours() * 60) / windowMinutes;
		List<Double> returns = new ArrayList<>();
		for (int i = 0; i < segments; i++) {
			LocalDateTime segmentEnd = now.minusMinutes((long)i * windowMinutes);
			LocalDateTime segmentStart = now.minusMinutes((long)(i + 1) * windowMinutes);
			Optional<PriceSnapshotDto> endSnapshot = nearest(byTime, segmentEnd);
			Optional<PriceSnapshotDto> startSnapshot = nearest(byTime, segmentStart);
			if (endSnapshot.isEmpty() || startSnapshot.isEmpty()) {
				continue;
			}
			double endPrice = endSnapshot.get().price().doubleValue();
			double startPrice = startSnapshot.get().price().doubleValue();
			if (endPrice <= 0 || startPrice <= 0) {
				continue;
			}
			returns.add(Math.log(endPrice / startPrice));
		}
		return returns;
	}

	private static double sampleStandardDeviation(List<Double> values) {
		double mean = 0;
		for (double value : values) {
			mean += value;
		}
		mean /= values.size();

		double squaredSum = 0;
		for (double value : values) {
			squaredSum += (value - mean) * (value - mean);
		}
		return Math.sqrt(squaredSum / (values.size() - 1));
	}

	private static NavigableMap<LocalDateTime, PriceSnapshotDto> indexByTime(List<PriceSnapshotDto> snapshots) {
		NavigableMap<LocalDateTime, PriceSnapshotDto> byTime = new TreeMap<>();
		for (PriceSnapshotDto snapshot : snapshots) {
			byTime.putIfAbsent(snapshot.recordedAt(), snapshot);
		}
		return byTime;
	}

	private static Optional<PriceSnapshotDto> nearest(
		NavigableMap<LocalDateTime, PriceSnapshotDto> byTime, LocalDateTime target) {
		Map.Entry<LocalDateTime, PriceSnapshotDto> floor = byTime.floorEntry(target);
		Map.Entry<LocalDateTime, PriceSnapshotDto> ceiling = byTime.ceilingEntry(target);
		Map.Entry<LocalDateTime, PriceSnapshotDto> closest = closerEntry(target, floor, ceiling);
		if (closest == null
			|| Duration.between(target, closest.getKey()).abs().compareTo(SNAPSHOT_TOLERANCE) > 0) {
			return Optional.empty();
		}
		return Optional.of(closest.getValue());
	}

	private static Map.Entry<LocalDateTime, PriceSnapshotDto> closerEntry(
		LocalDateTime target,
		Map.Entry<LocalDateTime, PriceSnapshotDto> floor,
		Map.Entry<LocalDateTime, PriceSnapshotDto> ceiling) {
		if (floor == null) {
			return ceiling;
		}
		if (ceiling == null) {
			return floor;
		}
		Duration floorDiff = Duration.between(floor.getKey(), target).abs();
		Duration ceilingDiff = Duration.between(target, ceiling.getKey()).abs();
		return floorDiff.compareTo(ceilingDiff) <= 0 ? floor : ceiling;
	}

	private static BigDecimal scaled(double value, int scale) {
		return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
	}

	private static PriceMovePromptDto toPrompt(
		Instrument instrument,
		LocalDateTime now,
		int rollingWindowMinutes,
		BigDecimal changeRate,
		List<MarketNewsItem> sources) {
		return new PriceMovePromptDto(
			instrument.getName(),
			false,
			now.minusMinutes(rollingWindowMinutes).toLocalTime(),
			now.toLocalTime(),
			rollingWindowMinutes,
			changeRate,
			now.toLocalDate(),
			sources.stream().map(CryptoPriceMoveWatcher::toSource).toList());
	}

	private static NewsSourceDto toSource(MarketNewsItem item) {
		return new NewsSourceDto(
			item.getTitle(), item.getPublisher(), item.getPublishedAt(),
			item.getType() == MarketNewsItemType.DISCLOSURE);
	}
}
