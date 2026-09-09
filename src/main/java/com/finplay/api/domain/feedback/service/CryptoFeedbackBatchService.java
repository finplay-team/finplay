package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoFeedbackBatchService {

	private final InstrumentService instrumentService;

	private final InstrumentNewsSummaryService instrumentNewsSummaryService;

	private final MarketBriefingService marketBriefingService;

	@Scheduled(cron = "${feedback.batch.crypto-cron}", zone = "Asia/Seoul")
	public void refreshCryptoFeedback() {
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

		boolean briefingRefreshed = false;
		try {
			briefingRefreshed = marketBriefingService.refreshCryptoBriefing().isPresent();
		} catch (RuntimeException ex) {
			log.warn("코인 브리핑 갱신에 실패해 이 단계를 건너뛴다.", ex);
		}
		log.info("코인 요약·브리핑 갱신을 마쳤다. 요약={}건 브리핑={}", refreshed, briefingRefreshed);
	}
}
