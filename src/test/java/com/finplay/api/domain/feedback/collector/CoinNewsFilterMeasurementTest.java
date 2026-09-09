package com.finplay.api.domain.feedback.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finplay.api.domain.feedback.service.NewsTitleFilter;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoinNewsFilterMeasurementTest {

	private static final Path DUMP_DIR = Path.of("tools", "coin-news-measure", "out");
	private static final Path REPORT = DUMP_DIR.resolve("report.md");

	private static final int SEEDED_COIN_COUNT = 12;

	private static final int MISMATCH_SAMPLE_LIMIT = 5;

	private final NewsTitleFilter titleFilter = new NewsTitleFilter();

	@Test
	@DisplayName("[측정] 코인 제목 필터 후보를 같은 덤프로 대조한다 (덤프가 없으면 건너뛴다)")
	void compareFilterCandidatesOnTheSameDump() throws IOException {
		List<Path> dumps = findDumps();
		Assumptions.assumeFalse(
			dumps.isEmpty(),
			"덤프가 없다. 먼저 `python tools/coin-news-measure/fetch.py`를 돌려라.");

		List<String> mismatches = new ArrayList<>();
		StringBuilder report = new StringBuilder("# 코인 뉴스 필터 후보 대조 (이슈 #179)\n");
		for (Path dump : dumps) {
			report.append(measure(dump, mismatches));
		}
		Files.writeString(REPORT, report);
		System.out.println(report);
		System.out.println("보고서 — " + REPORT.toAbsolutePath());

		assertThat(mismatches)
			.as("운영 판정과 측정기 합본 `S`가 건별로 갈렸다 — 보고서 %s", REPORT.toAbsolutePath())
			.isEmpty();
	}

	private String measure(Path dump, List<String> mismatches) throws IOException {
		JsonNode root = new ObjectMapper().readTree(Files.readString(dump));
		List<JsonNode> records = new ArrayList<>();
		root.get("records").forEach(records::add);

		List<String> allNames = records.stream().map(r -> r.get("name").asText()).toList();

		StringBuilder out = new StringBuilder("\n## 질의 방식 `" + root.get("label").asText() + "`\n\n");

		if (records.size() < SEEDED_COIN_COUNT) {
			out.append(String.format(
				"> **[경고] 부분 덤프다 — %d종목뿐이다** (V7 시드 코인 %d종). 종목 목록이 줄면 다른 종목명 "
					+ "등장 판정 `O`와 접두 보호가 둘 다 달라진다. 이 표의 숫자는 12종 측정과 나란히 놓을 수 "
					+ "없다 — 기준선과 대조하려면 `--only` 없이 다시 받아라.%n%n",
				records.size(), SEEDED_COIN_COUNT));
		}

		out.append("| 종목 | 질의 | 수신 | 개정 전 `!O` | 후보1 `S&&!O` | 후보2 `S\\|\\|!O` | 합본 `S` "
			+ "| 별칭 포함 | **운영 현재** |\n");
		out.append("|---|---|---:|---:|---:|---:|---:|---:|---:|\n");

		Totals totals = new Totals();
		StringBuilder splitRows = new StringBuilder();
		List<String> dumpMismatches = new ArrayList<>();
		for (JsonNode record : records) {
			String name = record.get("name").asText();
			String symbol = record.get("symbol").asText();
			Instrument instrument = crypto(symbol, name);

			Counts counts = new Counts();
			for (JsonNode item : record.get("items")) {
				String title = NaverNewsCollector.cleanTitle(item.get("title").asText());
				boolean noOther = noOtherNameAppears(title, name, allNames);
				boolean selfPresent = appearsIndependently(title, name, allNames);
				boolean selfOrAlias = selfPresent || containsSymbol(title, symbol);
				boolean production = titleFilter.isRelevant(instrument, allNames, title);
				counts.add(noOther, selfPresent, selfOrAlias, production);

				if (production != selfPresent) {
					dumpMismatches.add(String.format(
						"`%s` [%s] — 운영 %s / 합본 `S` %s — \"%s\"",
						root.get("label").asText(), name,
						production ? "통과" : "제외", selfPresent ? "통과" : "제외", title));
				}
			}
			totals.add(counts);

			out.append(String.format(
				"| %s | `%s` | %d | %d | %d | %d | %d | %d | %d |%n",
				name, record.get("query").asText(), counts.received,
				counts.current, counts.candidate1, counts.candidate2, counts.combined, counts.withAlias,
				counts.production));

			splitRows.append(String.format(
				"| %s | %d | %d | %d |%n",
				name, counts.received - counts.current, counts.excludedButSelfPresent,
				counts.received - counts.current - counts.excludedButSelfPresent));
		}

		out.append(String.format(
			"| **합계** | | **%d** | **%d** | **%d** | **%d** | **%d** | **%d** | **%d** |%n",
			totals.received, totals.current, totals.candidate1,
			totals.candidate2, totals.combined, totals.withAlias, totals.production));

		out.append(String.format(
			"%n> **운영 현재 ↔ 합본 `S` (건별 대조)** — %s · %d건 대조, 어긋남 **%d건** (합계 %d vs %d)%n",
			dumpMismatches.isEmpty() ? "전건 일치" : "**어긋남 — 테스트 실패**",
			totals.received, dumpMismatches.size(), totals.production, totals.combined));
		dumpMismatches.stream()
			.limit(MISMATCH_SAMPLE_LIMIT)
			.forEach(mismatch -> out.append(String.format(">   - %s%n", mismatch)));
		if (dumpMismatches.size() > MISMATCH_SAMPLE_LIMIT) {
			out.append(String.format(
				">   - … 외 %d건%n", dumpMismatches.size() - MISMATCH_SAMPLE_LIMIT));
		}
		mismatches.addAll(dumpMismatches);

		out.append("\n### 제외분을 둘로 가르면 (후보2가 살릴 수 있는 몫)\n\n");
		out.append("| 종목 | 현행 제외 | (a) 자기 이름 있음 → **후보2가 살린다** | (b) 자기 이름 없음 → 못 살린다 |\n");
		out.append("|---|---:|---:|---:|\n").append(splitRows);
		out.append(String.format(
			"| **합계** | **%d** | **%d** | **%d** |%n",
			totals.received - totals.current, totals.excludedButSelfPresent,
			totals.received - totals.current - totals.excludedButSelfPresent));

		out.append("\n### 개정 전 통과분 중 자기 이름이 없던 것 (= 오탐, 개정으로 걷힌 몫)\n\n");
		out.append("| 종목 | 개정 전 통과 | 그중 자기 이름 없음 |\n|---|---:|---:|\n");
		int totalPassed = 0;
		int totalPassedWithoutSelf = 0;
		for (JsonNode record : records) {
			String name = record.get("name").asText();
			int passed = 0;
			int passedWithoutSelf = 0;
			for (JsonNode item : record.get("items")) {
				String title = NaverNewsCollector.cleanTitle(item.get("title").asText());
				if (noOtherNameAppears(title, name, allNames)) {
					passed++;
					if (!appearsIndependently(title, name, allNames)) {
						passedWithoutSelf++;
					}
				}
			}
			totalPassed += passed;
			totalPassedWithoutSelf += passedWithoutSelf;
			out.append(String.format("| %s | %d | %d |%n", name, passed, passedWithoutSelf));
		}
		out.append(String.format(
			"| **합계** | **%d** | **%d** (%s) |%n",
			totalPassed, totalPassedWithoutSelf, percentage(totalPassedWithoutSelf, totalPassed)));

		out.append("\n> **별칭 열은 참고용이다.** 심볼을 자기 이름으로 함께 인정한 결과인데, `ETC`·`DOT`처럼 "
			+ "영어 일반어와 겹치는 심볼이 있어 그대로 쓰면 새 오탐이 생긴다. 숫자가 크게 달라지는 종목만 "
			+ "제목을 눈으로 확인한다.\n");
		return out.toString();
	}

	private static boolean appearsIndependently(String title, String selfName, List<String> allNames) {
		for (int selfStart : occurrenceStarts(title, selfName)) {
			boolean swallowed = false;
			for (String other : allNames) {
				if (other.equals(selfName) || other.length() <= selfName.length()) {
					continue;
				}
				for (int otherStart : occurrenceStarts(title, other)) {
					if (otherStart <= selfStart && selfStart + selfName.length() <= otherStart + other.length()) {
						swallowed = true;
						break;
					}
				}
				if (swallowed) {
					break;
				}
			}
			if (!swallowed) {
				return true;
			}
		}
		return false;
	}

	private static boolean noOtherNameAppears(String title, String selfName, List<String> allNames) {
		List<Integer> selfStarts = occurrenceStarts(title, selfName);
		for (String other : allNames) {
			if (other.equals(selfName)) {
				continue;
			}
			for (int otherStart : occurrenceStarts(title, other)) {
				boolean covered = false;
				for (int selfStart : selfStarts) {
					if (selfStart <= otherStart
						&& otherStart + other.length() <= selfStart + selfName.length()) {
						covered = true;
						break;
					}
				}
				if (!covered) {
					return false;
				}
			}
		}
		return true;
	}

	private static String percentage(int part, int whole) {
		return whole == 0 ? "-" : String.format("%.0f%%", 100.0 * part / whole);
	}

	private static boolean containsSymbol(String title, String symbol) {
		return title.toUpperCase(Locale.ROOT).contains(symbol.toUpperCase(Locale.ROOT));
	}

	private static List<Integer> occurrenceStarts(String text, String keyword) {
		List<Integer> starts = new ArrayList<>();
		for (int i = text.indexOf(keyword); i >= 0; i = text.indexOf(keyword, i + 1)) {
			starts.add(i);
		}
		return starts;
	}

	private static Instrument crypto(String symbol, String name) {
		return Instrument.create(
			Market.CRYPTO, symbol, name, new BigDecimal("0.1"), 5000L, true, LocalDateTime.now());
	}

	private static List<Path> findDumps() throws IOException {
		if (!Files.isDirectory(DUMP_DIR)) {
			return List.of();
		}
		try (var paths = Files.list(DUMP_DIR)) {
			return paths.filter(p -> p.getFileName().toString().startsWith("raw-")).sorted().toList();
		}
	}

	private static final class Counts {

		private int received;
		private int current;
		private int candidate1;
		private int candidate2;
		private int combined;
		private int withAlias;
		private int production;
		private int excludedButSelfPresent;

		private void add(boolean noOther, boolean selfPresent, boolean selfOrAlias, boolean passedProduction) {
			received++;
			if (passedProduction) {
				production++;
			}
			if (noOther) {
				current++;
			}
			if (selfPresent && noOther) {
				candidate1++;
			}
			if (selfPresent || noOther) {
				candidate2++;
			}
			if (selfPresent) {
				combined++;
			}
			if (selfOrAlias) {
				withAlias++;
			}
			if (!noOther && selfPresent) {
				excludedButSelfPresent++;
			}
		}
	}

	private static final class Totals {

		private int received;
		private int current;
		private int candidate1;
		private int candidate2;
		private int combined;
		private int withAlias;
		private int production;
		private int excludedButSelfPresent;

		private void add(Counts counts) {
			received += counts.received;
			current += counts.current;
			candidate1 += counts.candidate1;
			candidate2 += counts.candidate2;
			combined += counts.combined;
			withAlias += counts.withAlias;
			production += counts.production;
			excludedButSelfPresent += counts.excludedButSelfPresent;
		}
	}
}
