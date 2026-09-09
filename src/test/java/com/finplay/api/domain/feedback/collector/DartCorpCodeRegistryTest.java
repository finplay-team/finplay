package com.finplay.api.domain.feedback.collector;

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

class DartCorpCodeRegistryTest {

	private static final String RESOURCE_PATH = "/dart-corp-codes.txt";

	private static final String SEED_MIGRATION_PATH = "/db/migration/V7__create_instruments.sql";

	private static final Pattern DATA_LINE = Pattern.compile("\\d{6}=\\d{8}");

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

	@Test
	@DisplayName("매핑이 V7 시드의 주식 전 종목을 덮는다")
	void coversEveryStockInTheV7Seed() throws IOException {
		List<String> seedSymbols = readSeedStockSymbols();

		assertThat(seedSymbols).as("V7 시드에서 주식 종목을 한 건도 읽지 못하면 이 테스트는 공허하다").isNotEmpty();
		assertThat(seedSymbols).allSatisfy(symbol -> assertThat(registry.findCorpCode(symbol))
			.as("V7 시드의 %s가 dart-corp-codes.txt에 없다 — 이 종목만 공시가 0건이 된다", symbol)
			.isPresent());
	}

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
