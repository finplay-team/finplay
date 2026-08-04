// 종목코드 → OpenDART 고유번호(corp_code) 매핑을 리소스 파일에서 1회만 읽어 제공하는 컴포넌트.
package com.finplay.api.feedback.collector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <b>OpenDART는 종목코드가 아니라 {@code corp_code}로 조회한다</b>(spec §외부 API 호출 상세). 둘 사이에 규칙이
 * 없어 매핑을 미리 리소스로 두고 읽는다 — 형식과 채우는 방법은 {@code dart-corp-codes.txt} 머리말에 있다
 * ({@code holidays-2026.txt}와 같은 방식).
 *
 * <p><b>매핑에 없는 종목은 오류가 아니라 건너뛴다</b>(FEED-001). 그래서 조회는 {@code Optional}을 돌려주고
 * 예외를 던지지 않는다.
 *
 * <p>다만 <b>비어 있는 매핑은 기동 로그에 남긴다.</b> 매핑이 0건이면 공시가 영원히 0건인데 예외도 경고도 없어
 * "되고 있는 줄 알았다"가 되기 쉽다 — 이 기능에서 가장 조용한 실패 모양이라 눈에 띄게 한다.
 *
 * <p>형식이 어긋난 줄은 <b>기동을 막는다.</b> {@code corp_code}는 8자리 숫자라 오타가 나도 형식만 맞으면 API가
 * 200을 돌려주고 <b>다른 회사의 공시</b>를 주기 때문에, 적어도 형식 오류만큼은 런타임까지 끌고 가지 않는다.
 *
 * <p>생성자가 리소스 로딩 실패로 예외를 던질 수 있어 클래스를 {@code final}로 선언한다
 * (SpotBugs {@code CT_CONSTRUCTOR_THROW} 권고 — {@code BusinessDayCalendar} 선례).
 */
@Slf4j
@Component
public final class DartCorpCodeRegistry {

	private static final String CORP_CODE_RESOURCE_PATH = "/dart-corp-codes.txt";
	private static final Pattern CORP_CODE_FORMAT = Pattern.compile("\\d{8}");

	private final Map<String, String> corpCodesBySymbol;

	public DartCorpCodeRegistry() {
		this.corpCodesBySymbol = loadCorpCodes(CORP_CODE_RESOURCE_PATH);
		if (corpCodesBySymbol.isEmpty()) {
			log.warn("OpenDART corp_code 매핑이 비어 있어 공시를 한 건도 수집하지 않습니다."
				+ " 채우는 방법은 {} 머리말에 있습니다.", CORP_CODE_RESOURCE_PATH);
		} else {
			log.info("OpenDART corp_code 매핑 {}건을 읽었습니다.", corpCodesBySymbol.size());
		}
	}

	/**
	 * 종목코드에 대응하는 {@code corp_code}를 찾는다.
	 *
	 * @return 매핑이 없으면 {@code Optional.empty()} — 오류가 아니라 그 종목을 건너뛰라는 뜻이다
	 */
	public Optional<String> findCorpCode(String symbol) {
		return Optional.ofNullable(corpCodesBySymbol.get(symbol));
	}

	public int size() {
		return corpCodesBySymbol.size();
	}

	// 한 줄에 symbol=corp_code, # 로 시작하는 줄과 빈 줄은 주석이다 (holidays-2026.txt와 같은 규칙).
	private static Map<String, String> loadCorpCodes(String resourcePath) {
		try (InputStream inputStream = DartCorpCodeRegistry.class.getResourceAsStream(resourcePath)) {
			if (inputStream == null) {
				throw new IllegalStateException("OpenDART corp_code 리소스 파일을 찾을 수 없습니다: " + resourcePath);
			}
			try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
				Map<String, String> corpCodes = new LinkedHashMap<>();
				String line;
				while ((line = reader.readLine()) != null) {
					parseLine(line, resourcePath).ifPresent(entry -> corpCodes.put(entry.symbol(), entry.corpCode()));
				}
				return Map.copyOf(corpCodes);
			}
		} catch (IOException ex) {
			throw new IllegalStateException("OpenDART corp_code 리소스 파일을 읽지 못했습니다: " + resourcePath, ex);
		}
	}

	private static Optional<CorpCodeEntry> parseLine(String rawLine, String resourcePath) {
		String line = rawLine.trim();
		if (line.isEmpty() || line.startsWith("#")) {
			return Optional.empty();
		}
		int separator = line.indexOf('=');
		if (separator < 0) {
			throw new IllegalStateException(
				"OpenDART corp_code 리소스의 형식이 잘못되었습니다 (symbol=corp_code 여야 합니다): " + resourcePath + " → " + line);
		}
		String symbol = line.substring(0, separator).trim();
		String corpCode = line.substring(separator + 1).trim();
		if (symbol.isEmpty() || !CORP_CODE_FORMAT.matcher(corpCode).matches()) {
			throw new IllegalStateException(
				"OpenDART corp_code는 8자리 숫자여야 합니다: " + resourcePath + " → " + line);
		}
		return Optional.of(new CorpCodeEntry(symbol, corpCode));
	}

	private record CorpCodeEntry(String symbol, String corpCode) {
	}
}
