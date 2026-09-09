package com.finplay.api.domain.feedback.collector;

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

	public Optional<String> findCorpCode(String symbol) {
		return Optional.ofNullable(corpCodesBySymbol.get(symbol));
	}

	public int size() {
		return corpCodesBySymbol.size();
	}

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
