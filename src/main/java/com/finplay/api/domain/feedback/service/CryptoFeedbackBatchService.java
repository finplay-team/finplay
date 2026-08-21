// 코인 요약·브리핑을 매시 갱신하는 배치의 오케스트레이션 — 순서를 세우고 각 단계를 부르기만 한다.
package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 크론 값은 {@code application.yml}의 {@code feedback.batch.crypto-cron}이고 정본은 spec §C-1이다.
 *
 * <p><b>왜 주식과 배치가 다른가.</b> 코인은 24시간 거래라 '개장 전'이라는 고정 시점이 없어 하루치를 미리 만들어
 * 둘 자리가 없다(FEED-008·FEED-009). 대신 매시 갱신해 최근 24시간을 따라가며, 범위도 {@code ROLLING_24H}
 * 하나뿐이라 주식처럼 두 요약을 순서대로 만들 필요가 없다.
 *
 * <p><b>조회 시 생성하지 않기 위한 배치다.</b> GET이 외부 LLM을 호출하고 DB에 쓰면 "GET은 부수효과 없음"을
 * 어기고, 갱신 직후 동시 요청이 전부 LLM을 호출하며, 그 순간의 첫 사용자가 최대 40초를 기다린다. 배치로 옮기면
 * 셋 다 사라지고 <b>호출량이 사용자 수와 무관하게 고정된다.</b>
 *
 * <p><b>이 클래스는 트랜잭션을 열지 않는다</b>({@code FeedbackBatchService}와 같은 이유). LLM 호출이 트랜잭션
 * 안에 들어가면 종목 수만큼 커넥션을 길게 점유한다.
 *
 * <p><b>탐지·감시는 여기 없다.</b> 코인 변동 감시({@code CryptoPriceMoveWatcher})와 가격 스냅샷은 별도 크론이고
 * 다른 이슈 소유다(§C-1) — 이 배치의 코인 작업은 요약·브리핑뿐이다.
 *
 * <p><b>수집은 이 배치에 포함하지 않는다.</b> 수집은 기사가 나오는 당일에 상시로 도는 별도 스케줄이 맡고
 * (FEED-001) 이 배치는 이미 저장된 기사를 읽기만 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoFeedbackBatchService {

	private final InstrumentService instrumentService;

	private final InstrumentNewsSummaryService instrumentNewsSummaryService;

	private final MarketBriefingService marketBriefingService;

	/**
	 * 코인 요약·브리핑 갱신 진입점.
	 *
	 * <p><b>{@code zone}을 반드시 붙인다</b>(§C-1). {@code ClockConfig}의 {@code Clock} 빈은 KST지만
	 * {@code @Scheduled}는 그 빈을 쓰지 않고 JVM 기본 타임존을 따르는데, {@code Dockerfile}·
	 * {@code compose.deploy.yaml}에 {@code TZ}가 없어 배포 JVM 기본이 UTC다. 빠뜨리면 <b>예외도 로그도 없이</b>
	 * 9시간 어긋난 시각에 돈다. 매시 크론이라 어긋남이 눈에 덜 띄는 만큼 더 위험하다 —
	 * {@code origin_trade_date}가 KST 날짜라 자정 부근에서 하루가 밀린 행이 생긴다.
	 *
	 * <p><b>재생세션을 보지 않는다.</b> 코인은 재생 시간축이 없어 주식의 {@code READY} 확인이 성립하지 않는다.
	 *
	 * <p><b>종목 하나가 실패해도 나머지는 계속한다</b>(§실패 처리). 브리핑도 요약과 분리해 격리하므로 한쪽이
	 * 터져도 다른 쪽은 갱신된다 — 배치 전체를 실패시키지 않는다.
	 *
	 * <p>요약을 먼저, 브리핑을 나중에 부르는 것은 비용 순서일 뿐 주식의 §C-6 생성 순서 같은 노출 제약이 아니다.
	 * 코인은 노출 게이트가 없어 무엇이 먼저 만들어지든 화면에 차이가 없다.
	 */
	@Scheduled(cron = "${feedback.batch.crypto-cron}", zone = "Asia/Seoul")
	public void refreshCryptoFeedback() {
		// 샌드박스 튜토리얼 종목은 제외한다 (이슈 #406) — 이유는 FeedbackBatchService와 같다.
		List<Instrument> instruments = instrumentService.getRealInstrumentEntities(Market.CRYPTO);
		log.info("코인 요약·브리핑 갱신을 시작한다. 종목={}건", instruments.size());

		int refreshed = 0;
		for (Instrument instrument : instruments) {
			try {
				if (instrumentNewsSummaryService.refreshCryptoSummary(instrument).isPresent()) {
					refreshed++;
				}
			} catch (RuntimeException ex) {
				log.warn("코인 요약 갱신에 실패해 이 종목을 건너뛴다. 종목={}", instrument.getId(), ex);
			}
		}

		// 비어 있는 결과는 오류가 아니다 — 직전 생성 이후 새 기사가 없거나 최근 24시간 기사가 0건인 시각이다.
		boolean briefingRefreshed = false;
		try {
			briefingRefreshed = marketBriefingService.refreshCryptoBriefing().isPresent();
		} catch (RuntimeException ex) {
			log.warn("코인 브리핑 갱신에 실패해 이 단계를 건너뛴다.", ex);
		}
		log.info("코인 요약·브리핑 갱신을 마쳤다. 요약={}건 브리핑={}", refreshed, briefingRefreshed);
	}
}
