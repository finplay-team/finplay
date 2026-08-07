// 코인 뉴스 제목 필터 후보를 같은 데이터로 대조하는 측정기 (이슈 #179 1단계) — 외부 호출 없이 덤프만 읽는다.
package com.finplay.api.feedback.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finplay.api.feedback.service.NewsTitleFilter;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
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

/**
 * <b>이것은 회귀 테스트가 아니라 측정기다.</b> 단정하는 것이 없고, 덤프가 없으면 건너뛴다 — 빌드를 막지 않는다.
 * 이슈 #179의 완료 조건이 "같은 방법으로 재측정해 개선을 수치로 보인다"라서, 다음 사람이 같은 표를 다시 뽑을
 * 수 있어야 한다. 임시 프로브로 만들고 지우면 그게 안 된다.
 *
 * <p><b>외부 API를 부르지 않는다</b>(ADR-0011, PRD C-005). 네이버 호출은 {@code tools/coin-news-measure/fetch.py}가
 * 사람 손으로 한 번 하고, 이 클래스는 그 덤프만 읽는다. 그래서 후보를 몇 번이든 다시 대조할 수 있다 — 후보마다
 * 새로 받으면 그 사이 기사 목록이 바뀌어 무엇 때문에 숫자가 달라졌는지 구분되지 않는다.
 *
 * <p><b>제목 손질은 운영 코드가 한다.</b> {@link NaverNewsCollector#cleanTitle}을 그대로 부른다 — 네이버가
 * 질의어를 {@code <b>}로 감싸 보내므로 씻지 않은 제목으로 종목명을 찾으면 이름이 태그로 쪼개져 필터가 통째로
 * 무력해진다. 손질을 여기서 다시 구현하면 두 벌이 되어 <b>운영과 다른 규칙을 재게 된다.</b>
 *
 * <h2>무엇을 대조하는가</h2>
 *
 * 제목 하나를 두 가지로만 본다.
 *
 * <ul>
 *   <li><b>S</b> — 자기 종목명이 <b>독립적으로</b> 등장한다 (다른 종목명 안에 통째로 갇혀 있지 않다)</li>
 *   <li><b>O</b> — 다른 종목명이 독립적으로 등장한다. 이 판정은 운영 코드 {@link NewsTitleFilter}가 한다</li>
 * </ul>
 *
 * <pre>
 * 현행   통과 = !O          자기 이름이 있는지 아예 안 본다
 * 후보1  통과 = S &amp;&amp; !O     자기 이름 필수 — 오탐(방향②)을 막는다
 * 후보2  통과 = S || !O     제외를 좁힌다 — 시세 브리핑 누락(방향①)을 살린다
 * 합본   통과 = S           후보 1·2는 서로 다른 칸을 바꿔서 겹치지 않는다
 * </pre>
 *
 * <p><b>S 계산은 이 측정기 안에만 있다.</b> {@link NewsTitleFilter}가 지금 O만 판정하기 때문이다. 규칙을 고른
 * 뒤 운영 코드에 넣고 나면 <b>같은 덤프로 다시 돌려</b> 이 숫자가 재현되는지 확인해야 한다 — 여기 계산과
 * 구현이 갈릴 수 있는 자리다.
 */
class CoinNewsFilterMeasurementTest {

	private static final Path DUMP_DIR = Path.of("tools", "coin-news-measure", "out");
	private static final Path REPORT = DUMP_DIR.resolve("report.md");

	private final NewsTitleFilter titleFilter = new NewsTitleFilter();

	@Test
	@DisplayName("[측정] 코인 제목 필터 후보를 같은 덤프로 대조한다 (덤프가 없으면 건너뛴다)")
	void compareFilterCandidatesOnTheSameDump() throws IOException {
		List<Path> dumps = findDumps();
		Assumptions.assumeFalse(
			dumps.isEmpty(),
			"덤프가 없다. 먼저 `python tools/coin-news-measure/fetch.py`를 돌려라.");

		StringBuilder report = new StringBuilder("# 코인 뉴스 필터 후보 대조 (이슈 #179)\n");
		for (Path dump : dumps) {
			report.append(measure(dump));
		}
		Files.writeString(REPORT, report);
		System.out.println(report);
		System.out.println("보고서 — " + REPORT.toAbsolutePath());
	}

	private String measure(Path dump) throws IOException {
		JsonNode root = new ObjectMapper().readTree(Files.readString(dump));
		List<JsonNode> records = new ArrayList<>();
		root.get("records").forEach(records::add);

		List<String> allNames = records.stream().map(r -> r.get("name").asText()).toList();

		StringBuilder out = new StringBuilder("\n## 질의 방식 `" + root.get("label").asText() + "`\n\n");
		out.append("| 종목 | 질의 | 수신 | 개정 전 `!O` | 후보1 `S&&!O` | 후보2 `S\\|\\|!O` | 합본 `S` "
			+ "| 별칭 포함 | **운영 현재** |\n");
		out.append("|---|---|---:|---:|---:|---:|---:|---:|---:|\n");

		Totals totals = new Totals();
		StringBuilder splitRows = new StringBuilder();
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
				// 운영 코드가 지금 무엇을 통과시키는지. 규칙을 바꾼 뒤 이 열이 고른 후보와 일치해야 한다.
				boolean production = titleFilter.isRelevant(instrument, allNames, title);
				counts.add(noOther, selfPresent, selfOrAlias, production);
			}
			totals.add(counts);

			out.append(String.format(
				"| %s | `%s` | %d | %d | %d | %d | %d | %d | %d |%n",
				name, record.get("query").asText(), counts.received,
				counts.current, counts.candidate1, counts.candidate2, counts.combined, counts.withAlias,
				counts.production));

			// 이슈가 "제외분의 대부분이 시세 브리핑"이라고만 적어 둔 것을 둘로 가른다. 후보2가 실제로 얼마나
			// 살리는지가 여기서 갈린다 — (b)는 어떤 후보로도 살아나지 않는다.
			splitRows.append(String.format(
				"| %s | %d | %d | %d |%n",
				name, counts.received - counts.current, counts.excludedButSelfPresent,
				counts.received - counts.current - counts.excludedButSelfPresent));
		}

		out.append(String.format(
			"| **합계** | | **%d** | **%d** | **%d** | **%d** | **%d** | **%d** | **%d** |%n",
			totals.received, totals.current, totals.candidate1,
			totals.candidate2, totals.combined, totals.withAlias, totals.production));

		// 규칙을 바꾼 뒤 이 줄이 "일치"여야 구현이 고른 후보와 같다는 근거가 된다. 어긋나면 코드가 문서와
		// 다른 규칙을 돌리고 있다는 뜻이므로, 숫자만 보고 넘기지 말고 여기서 걸러야 한다.
		out.append(String.format(
			"%n> **운영 현재 ↔ 합본 `S`** — %s (%d vs %d)%n",
			totals.production == totals.combined ? "일치" : "**어긋남**",
			totals.production, totals.combined));

		out.append("\n### 제외분을 둘로 가르면 (후보2가 살릴 수 있는 몫)\n\n");
		out.append("| 종목 | 현행 제외 | (a) 자기 이름 있음 → **후보2가 살린다** | (b) 자기 이름 없음 → 못 살린다 |\n");
		out.append("|---|---:|---:|---:|\n").append(splitRows);

		out.append("\n### 개정 전 통과분 중 자기 이름이 없던 것 (= 오탐, 개정으로 걷힌 몫)\n\n");
		out.append("| 종목 | 개정 전 통과 | 그중 자기 이름 없음 |\n|---|---:|---:|\n");
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
			out.append(String.format("| %s | %d | %d |%n", name, passed, passedWithoutSelf));
		}

		out.append("\n> **별칭 열은 참고용이다.** 심볼을 자기 이름으로 함께 인정한 결과인데, `ETC`·`DOT`처럼 "
			+ "영어 일반어와 겹치는 심볼이 있어 그대로 쓰면 새 오탐이 생긴다. 숫자가 크게 달라지는 종목만 "
			+ "제목을 눈으로 확인한다.\n");
		return out.toString();
	}

	/**
	 * 자기 종목명이 다른 종목명 안에 통째로 갇히지 않은 채 등장하는지 본다 — {@link NewsTitleFilter}의 판정을
	 * 방향만 뒤집은 것이다.
	 *
	 * <p>단순 포함 검사로 두면 접두 관계에서 틀린다. {@code 비트코인} 수집 중 제목 {@code "비트코인캐시 급등"}은
	 * {@code 비트코인}을 포함하지만 그 등장은 {@code 비트코인캐시}를 읽은 것일 뿐이라 <b>남의 기사</b>다.
	 */
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

	/**
	 * 개정 <b>전</b> 규칙 — 다른 종목명이 독립적으로 등장하지 않으면 통과. 운영 코드에서 코인은 이 규칙을 더
	 * 이상 쓰지 않으므로(2026-08-07 개정) 여기에 복제해 둔다. <b>기준선을 재현하려면 옛 규칙이 필요하다</b> —
	 * 이 열이 없으면 개정 전후를 같은 데이터로 대조할 수 없다. 주식 경로는 지금도 이 규칙이다.
	 */
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

	// 심볼은 대소문자를 가리지 않는다. 영어 단어 경계는 보지 않는다 — 그 거친 판정이 만드는 오탐을 보는 것이
	// 이 열의 목적이다(ETC/DOT).
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

		private void add(Counts counts) {
			received += counts.received;
			current += counts.current;
			candidate1 += counts.candidate1;
			candidate2 += counts.candidate2;
			combined += counts.combined;
			withAlias += counts.withAlias;
			production += counts.production;
		}
	}
}
