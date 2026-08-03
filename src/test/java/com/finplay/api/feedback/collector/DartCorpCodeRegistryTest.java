// dart-corp-codes.txt에 적힌 줄이 형식대로 읽혀 조회에 쓰이는지, 미등재 종목이 오류가 아닌지 검증한다.
package com.finplay.api.feedback.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 이 테스트는 tasks.md 4번의 검증 ④("매핑 리소스가 §C-7의 주식 종목 전부를 덮는다")를 단정하지 않는다.
// corp_code의 정본은 DART_API_KEY로만 받을 수 있는 corpCode.xml이고 그 키가 아직 없어, 지금 ④를 통과시키려면
// 추측값을 넣거나 기대 종목 수를 낮춰 잡아야 한다 — 둘 다 완료 조건을 없애는 것이라 하지 않는다.
// 대신 여기서는 매핑이 채워졌을 때도 그대로 성립해야 하는 성질만 단정한다. 파일이 채워지면 이 테스트는
// 그대로 통과하고, ④는 별도 테스트로 그때 추가한다.
class DartCorpCodeRegistryTest {

	private static final String RESOURCE_PATH = "/dart-corp-codes.txt";

	// 파일 머리말이 정한 형식 — symbol 6자리, corp_code 8자리 숫자.
	private static final Pattern DATA_LINE = Pattern.compile("\\d{6}=\\d{8}");

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
