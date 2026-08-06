// 코인 롤링 수익률을 매 분 감시해 임계치를 넘으면 카드를 확정하는 배치 — 탐지·쿨다운·근거 매칭·저장을 전부 담는다.
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.feedback.config.FeedbackDetectionProperties;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
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

/**
 * 수식의 정본은 spec §탐지 알고리즘(코인)이고 임계치는 §C-7이다. 여기서 값을 새로 정하지 않는다 — {@code k}는
 * 새로 만들지 않고 {@code feedback.detection.z-score-k}를 그대로 쓴다(주식·코인이 같은 계수를 공유).
 *
 * <p><b>카드 1건을 확정하는 경로를 {@code PriceMoveCardService}처럼 여기서도 직접 갖는다.</b> 주식과 달리 코인은
 * 탐지·근거 매칭·서술·저장이 전부 매 분 한 번에 일어나므로(재생 시간축·개장 전 배치가 없다) 별도 확정 서비스로
 * 쪼갤 경계가 없다. 저장만은 {@code PriceMoveCardWriter}를 그대로 재사용한다 — 저장 대상 테이블이 같다.
 *
 * <p><b>다른 도메인 데이터는 서비스를 경유한다</b>(§C-6). 코인 가격은 {@code CryptoPriceSnapshotService}만 알고
 * {@code PriceStore}를 직접 주입하지 않는다.
 *
 * <p><b>쓰기는 {@code price_move_events}·{@code price_move_event_sources} 둘뿐이다</b>(원장 불변, 8개 이슈 공통
 * 조건). 나머지는 전부 읽기다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoPriceMoveWatcher {

	// changeRate·detectionScore 컬럼의 스케일이다 (§C-8 DECIMAL(10,6)·DECIMAL(10,4)) — PriceMoveDetector와 같다.
	private static final int CHANGE_RATE_SCALE = 6;

	private static final int DETECTION_SCORE_SCALE = 4;

	// p_now·p_past·σ 표본 양 끝 스냅샷을 찾을 때 허용하는 시각 오차 (§탐지 알고리즘(코인) — p_past의
	// "허용 오차 ±1분. 벗어나면 없는 것으로 본다"). 스냅샷은 매 분 정각에 기록되고 이 감시는 매 분 30초에
	// 도는데 그 자연스러운 오차는 최대 수십 초뿐이라, p_now와 σ 표본 경계에도 같은 허용치를 적용해도
	// 정상 상태에서는 걸리지 않는다 — 큰 오차는 곧 피드 단절이나 기동 직후로 표본이 비어 있는 신호다.
	private static final Duration SNAPSHOT_TOLERANCE = Duration.ofMinutes(1);

	private final InstrumentService instrumentService;

	private final CryptoPriceSnapshotService cryptoPriceSnapshotService;

	private final PriceMoveEventRepository priceMoveEventRepository;

	private final PriceMoveCardWriter priceMoveCardWriter;

	private final NewsMatcher newsMatcher;

	private final NarrativeService narrativeService;

	private final FeedbackCryptoProperties cryptoProperties;

	private final FeedbackDetectionProperties detectionProperties;

	private final Clock clock;

	/**
	 * 코인 전 종목을 매 분 감시한다. 크론은 {@code feedback.batch.crypto-watch-cron}이다.
	 *
	 * <p><b>{@code zone}을 반드시 붙인다</b>(§C-1). 배포 JVM 기본 타임존이 UTC라 빠뜨리면 예외도 로그도 없이
	 * 엉뚱한 시각에 돈다.
	 *
	 * <p><b>종목 하나가 실패해도 나머지는 계속한다</b>(§실패 처리와 같은 방침, {@code CryptoFeedbackBatchService}
	 * 참고) — 근거 매칭·LLM 호출이 종목마다 일어나 한 종목의 오류가 나머지를 막으면 안 된다.
	 */
	@Scheduled(cron = "${feedback.batch.crypto-watch-cron}", zone = "Asia/Seoul")
	public void watch() {
		LocalDateTime now = LocalDateTime.now(clock);
		List<Instrument> instruments = instrumentService.getInstrumentEntities(Market.CRYPTO);
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

	// 의사코드 순서를 그대로 따른다 (§탐지 알고리즘(코인)) — p_now/p_past 조회 → 표본 부족·σ=0 종료 →
	// |r5|/σ24 < k 종료 → 쿨다운 → 일일 상한 → 근거 매칭(0건이면 종료) → 서술 → 저장.
	private boolean watchOne(Instrument instrument, LocalDateTime now) {
		int rollingWindowMinutes = cryptoProperties.rollingWindowMinutes();
		LocalDateTime lookbackStart = now.minusHours(cryptoProperties.sigmaLookbackHours());
		NavigableMap<LocalDateTime, PriceSnapshotDto> byTime = indexByTime(
			cryptoPriceSnapshotService.getSnapshots(instrument.getSymbol(), lookbackStart, now));

		// p_now 또는 p_past 없음 → 종료 (기동 직후·피드 단절).
		Optional<PriceSnapshotDto> pNow = nearest(byTime, now);
		Optional<PriceSnapshotDto> pPast = nearest(byTime, now.minusMinutes(rollingWindowMinutes));
		if (pNow.isEmpty() || pPast.isEmpty()) {
			return false;
		}

		List<Double> sample = buildNonOverlappingLogReturns(byTime, now, rollingWindowMinutes);
		// 표본 부족(기동 직후)과 σ=0(가격 불변)은 원인이 다르지만 둘 다 예외 없이 조용히 종료해야 한다.
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

		if (isWithinCooldown(instrument.getId(), now) || reachedDailyLimit(instrument.getId(), now)) {
			return false;
		}

		List<MarketNewsItem> sources = newsMatcher.matchCrypto(instrument.getId(), now);
		// 근거가 하나도 없으면 카드를 생성하지 않는다 (FEED-003과 동일 규칙).
		if (sources.isEmpty()) {
			return false;
		}

		BigDecimal changeRate = scaled(Math.expm1(r5), CHANGE_RATE_SCALE);
		BigDecimal detectionScore = scaled(score, DETECTION_SCORE_SCALE);
		NarrativeResultDto narrative = narrativeService.resolvePriceMoveNarrative(
			toPrompt(instrument, now, rollingWindowMinutes, changeRate, sources));
		PriceMoveEvent card = PriceMoveEvent.createCrypto(
			instrument, now, changeRate, detectionScore, narrative.narrative(), narrative.source(), now);
		priceMoveCardWriter.persist(card, sources);
		return true;
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

	/**
	 * σ 표본 — {@code rolling-window-minutes} 간격으로 자른 "겹치지 않는" 구간의 로그수익률 집합이다
	 * (§탐지 알고리즘(코인)). {@code now}에서 뒤로 걸어가며 경계를 잡으므로 {@code sigma-lookback-hours}가
	 * 24·{@code rolling-window-minutes}가 5면 최대 288개다. 매 분 슬라이딩으로 만들지 않는다 — 그러면 표본이
	 * 1440개가 되어 {@code min-sample-count} 도달 시점과 σ 값이 통째로 달라진다.
	 *
	 * <p>각 구간도 양 끝 스냅샷이 있을 때만 표본으로 만든다 — 한쪽이라도 없으면 그 구간은 건너뛴다.
	 */
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

	// 표본 표준편차 (n-1). PriceMoveDetector와 같은 식이며, 이 클래스가 호출하는 시점엔 이미
	// size() >= min-sample-count(기본 100)가 보장돼 있어 분모(n-1)가 0 이하가 될 수 없다.
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

	// target에 가장 가까운 스냅샷을 찾는다. SNAPSHOT_TOLERANCE를 벗어나면 없는 것으로 본다.
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

	// windowStart·windowEnd는 표시용 LocalTime일 뿐이다 — 자정을 넘으면 차가 음수가 되므로 windowMinutes를
	// 절대 시각(now)에서 직접 넘겨 구간 길이를 다시 계산하지 않게 한다 (PriceMovePromptDto 주석).
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
