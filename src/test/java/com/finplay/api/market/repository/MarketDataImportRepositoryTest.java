// 실제 MySQL에서 market_data_imports 저장·조회와 source_trading_date 기준 조회를 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.market.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.market.domain.ImportStatus;
import com.finplay.api.market.domain.MarketDataImport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class MarketDataImportRepositoryTest {

	@Autowired
	private MarketDataImportRepository marketDataImportRepository;

	private static final LocalDate SOURCE_TRADING_DATE = LocalDate.of(2026, 7, 22);
	private static final LocalDate OTHER_SOURCE_TRADING_DATE = LocalDate.of(2026, 7, 21);
	private static final LocalDateTime COLLECTED_AT = LocalDateTime.of(2026, 7, 23, 8, 10, 0);

	@Test
	void saveAndFindMarketDataImportPersistsAllFieldsCorrectly() {
		MarketDataImport saved = marketDataImportRepository.save(
			MarketDataImport.create("KIS_HISTORICAL", SOURCE_TRADING_DATE, COLLECTED_AT, ImportStatus.SUCCESS, null));

		Optional<MarketDataImport> found = marketDataImportRepository.findById(saved.getId());

		assertThat(found).isPresent();
		MarketDataImport marketDataImport = found.get();
		assertThat(marketDataImport.getSource()).isEqualTo("KIS_HISTORICAL");
		assertThat(marketDataImport.getSourceTradingDate()).isEqualTo(SOURCE_TRADING_DATE);
		assertThat(marketDataImport.getCollectedAt()).isNotNull();
		assertThat(marketDataImport.getStatus()).isEqualTo(ImportStatus.SUCCESS);
		assertThat(marketDataImport.getFailureReason()).isNull();
	}

	@Test
	void allFourImportStatusValuesAreMappedCorrectly() {
		MarketDataImport success = marketDataImportRepository.save(
			MarketDataImport.create("KIS_HISTORICAL", SOURCE_TRADING_DATE, COLLECTED_AT, ImportStatus.SUCCESS, null));
		MarketDataImport partialSuccess = marketDataImportRepository.save(
			MarketDataImport.create(
				"KIS_HISTORICAL", SOURCE_TRADING_DATE, COLLECTED_AT, ImportStatus.PARTIAL_SUCCESS,
				"000660 종목코드 형식 오류"));
		MarketDataImport failed = marketDataImportRepository.save(
			MarketDataImport.create(
				"KIS_HISTORICAL", SOURCE_TRADING_DATE, COLLECTED_AT, ImportStatus.FAILED, "응답 파싱 불가"));
		MarketDataImport skippedDuplicate = marketDataImportRepository.save(
			MarketDataImport.create(
				"KIS_HISTORICAL", SOURCE_TRADING_DATE, COLLECTED_AT, ImportStatus.SKIPPED_DUPLICATE, null));

		assertThat(marketDataImportRepository.findById(success.getId()).orElseThrow().getStatus())
			.isEqualTo(ImportStatus.SUCCESS);
		assertThat(marketDataImportRepository.findById(partialSuccess.getId()).orElseThrow().getStatus())
			.isEqualTo(ImportStatus.PARTIAL_SUCCESS);
		assertThat(marketDataImportRepository.findById(failed.getId()).orElseThrow().getStatus())
			.isEqualTo(ImportStatus.FAILED);
		assertThat(marketDataImportRepository.findById(skippedDuplicate.getId()).orElseThrow().getStatus())
			.isEqualTo(ImportStatus.SKIPPED_DUPLICATE);
	}

	@Test
	void findBySourceTradingDateOrderByCollectedAtDescReturnsOnlyThatDateOrderedNewestFirst() {
		marketDataImportRepository.save(
			MarketDataImport.create(
				"KIS_HISTORICAL", SOURCE_TRADING_DATE, COLLECTED_AT, ImportStatus.FAILED, "응답 파싱 불가"));
		marketDataImportRepository.save(
			MarketDataImport.create(
				"KIS_HISTORICAL", SOURCE_TRADING_DATE, COLLECTED_AT.plusMinutes(5), ImportStatus.SUCCESS, null));
		// 다른 거래일 — 결과에서 제외되어야 함
		marketDataImportRepository.save(
			MarketDataImport.create(
				"KIS_HISTORICAL", OTHER_SOURCE_TRADING_DATE, COLLECTED_AT, ImportStatus.SUCCESS, null));

		List<MarketDataImport> found = marketDataImportRepository
			.findBySourceTradingDateOrderByCollectedAtDesc(SOURCE_TRADING_DATE);

		assertThat(found).hasSize(2);
		assertThat(found).allMatch(marketDataImport -> marketDataImport.getSourceTradingDate()
			.equals(SOURCE_TRADING_DATE));
		assertThat(found.get(0).getStatus()).isEqualTo(ImportStatus.SUCCESS);
		assertThat(found.get(0).getCollectedAt()).isEqualTo(COLLECTED_AT.plusMinutes(5));
		assertThat(found.get(1).getStatus()).isEqualTo(ImportStatus.FAILED);
	}

	@Test
	void findBySourceTradingDateOrderByCollectedAtDescReturnsEmptyWhenNoImportExistsForThatDate() {
		List<MarketDataImport> found = marketDataImportRepository
			.findBySourceTradingDateOrderByCollectedAtDesc(SOURCE_TRADING_DATE);

		assertThat(found).isEmpty();
	}
}
