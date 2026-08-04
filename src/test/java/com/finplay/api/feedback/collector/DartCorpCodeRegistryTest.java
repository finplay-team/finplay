// dart-corp-codes.txt에 적힌 줄이 형식대로 읽혀 조회에 쓰이는지, 미등재 종목이 오류가 아닌지 검증한다.
package com.finplay.api.feedback.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// tasks.md 4번의 검증 ④("매핑 리소스가 §C-7의 주식 종목 전부를 덮는다")는 2026-08-04 DART_API_KEY 발급 후
// corpCode.xml에서 16종목을 받아 채우면서 닫혔다 — 아래 coversEveryStockInTheV7Seed가 그 조건이다.
// 기대 종목 목록을 이 파일에 박지 않고 V7 시드에서 읽는다. 상수로 두면 시드에 종목이 늘어도 테스트가
// 그대로 통과해, 새 종목의 공시만 조용히 0건이 되는 상태를 아무도 못 본다.
class DartCorpCodeRegistryTest {

	private static final String RESOURCE_PATH = "/dart-corp-codes.txt";

	private static final String SEED_MIGRATION_PATH = "/db/migration/V7__create_instruments.sql";

	// 파일 머리말이 정한 형식 — symbol 6자리, corp_code 8자리 숫자.
	private static final Pattern DATA_LINE = Pattern.compile("\\d{6}=\\d{8}");

	// V7 시드의 주식 행에서 종목코드를 뽑는다 — ('STOCK', '005930', '삼성전자', ...
	private static final Pattern SEED_STOCK = Pattern.compile("\\('STOCK',\\s*'(\\d{6})'");

	private final DartCorpCodeRegistry registry = new DartCorpCodeRegistry();

	@Test
	@DisplayName("리소스의 데이터 줄이 모두 형식에 맞고 한 줄도 빠짐없이 읽힌다")
	void loadsEveryWellFormedDataLineFromTheResource() throws IOException {
		List<String> dataLines = readDataLines();

		assertThat(dataLines).allSatisfy(line -> assertThat(line)
			.as("dart-corp-codes.txt의 데이터 줄은 symbol=corp_code(6자리=8자리) 형식이어야 한다")
			.matches(DATA_LINE));
		assertThat(registry.size())
			.as("파일에 적힌 줄이 조용히 누락되면 그 종목의 공시만 영원히 0건이 된다")
			.isEqualTo(dataLines.size());
	}

	// 등재된 심볼은 실제로 조회에 쓰여야 한다 — 파일에 있는데 findCorpCode가 못 찾으면 수집이 그 종목을
	// "매핑 없음"으로 조용히 건너뛴다.
	@Test
	@DisplayName("리소스에 등재된 심볼은 모두 그 corp_code로 조회된다")
	void findsCorpCodeForEverySymbolWrittenInTheResource() throws IOException {
		for (String line : readDataLines()) {
			String[] parts = line.split("=", 2);
			assertThat(registry.findCorpCode(parts[0]))
				.as("리소스에 적힌 %s가 조회되지 않는다", parts[0])
				.contains(parts[1]);
		}
	}

	// tasks.md 4번 검증 ④. 시드에 있는데 매핑에 없는 종목은 예외 없이 건너뛰어져 그 종목의 공시만 영원히
	// 0건이 되므로, 빠진 종목을 여기서 이름까지 짚어 실패시킨다.
	@Test
	@DisplayName("매핑이 V7 시드의 주식 전 종목을 덮는다")
	void coversEveryStockInTheV7Seed() throws IOException {
		List<String> seedSymbols = readSeedStockSymbols();

		assertThat(seedSymbols).as("V7 시드에서 주식 종목을 한 건도 읽지 못하면 이 테스트는 공허하다").isNotEmpty();
		assertThat(seedSymbols).allSatisfy(symbol -> assertThat(registry.findCorpCode(symbol))
			.as("V7 시드의 %s가 dart-corp-codes.txt에 없다 — 이 종목만 공시가 0건이 된다", symbol)
			.isPresent());
	}

	// 기대값의 출처는 V7 마이그레이션이다. 시드가 정본이므로 목록을 테스트에 복사하지 않는다.
	//
	// 파일 경로가 아니라 클래스패스로 읽는다 — Path.of("src/main/resources/...")는 작업 디렉터리가
	// 프로젝트 루트일 때만 맞아서 IDE 실행 구성에 따라 깨진다 (PR #174 리뷰 [참고]).
	private static List<String> readSeedStockSymbols() throws IOException {
		try (InputStream inputStream = DartCorpCodeRegistry.class.getResourceAsStream(SEED_MIGRATION_PATH)) {
			assertThat(inputStream).as("%s가 클래스패스에 없다", SEED_MIGRATION_PATH).isNotNull();
			Matcher matcher = SEED_STOCK.matcher(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
			List<String> symbols = new ArrayList<>();
			while (matcher.find()) {
				symbols.add(matcher.group(1));
			}
			return symbols;
		}
	}

	// FEED-001 — 매핑에 없는 종목은 오류가 아니라 건너뛴다. 예외를 던지면 그 종목 하나가 수집 전체를 멈춘다.
	@Test
	@DisplayName("매핑에 없는 종목은 예외가 아니라 빈 Optional이다")
	void returnsEmptyOptionalForUnmappedSymbol() {
		assertThat(registry.findCorpCode("999999")).isEmpty();
	}

	@Test
	@DisplayName("리소스 파일이 클래스패스에 실제로 존재한다")
	void resourceFileExistsOnClasspath() throws IOException {
		try (InputStream inputStream = DartCorpCodeRegistry.class.getResourceAsStream(RESOURCE_PATH)) {
			assertThat(inputStream).as("%s가 클래스패스에 없으면 기동이 실패한다", RESOURCE_PATH).isNotNull();
		}
	}

	// 주석(#)과 빈 줄을 뺀 실제 데이터 줄이다.
	private static List<String> readDataLines() throws IOException {
		try (InputStream inputStream = DartCorpCodeRegistry.class.getResourceAsStream(RESOURCE_PATH)) {
			assertThat(inputStream).isNotNull();
			String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			return content.lines()
				.map(String::trim)
				.filter(line -> !line.isEmpty() && !line.startsWith("#"))
				.toList();
		}
	}
}
