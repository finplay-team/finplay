// LLM이 만든 서술에서 금지 표현을 찾아내는 후검증기 — 판정만 하고 대체는 하지 않는다.
package com.finplay.api.domain.feedback.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 프롬프트 지시만으로는 부족하다는 전제에서 출발한다 (ADR-0011). 목록은 spec §후검증의 5줄 그대로이며
 * <b>임의로 넓히거나 좁히지 않는다</b> — 통과·차단의 경계 자체가 완료 조건이다. 예를 들어 "매도했습니다"는
 * 통과해야 하는데, 목록에 {@code 매도하세요}만 있고 {@code 매도하}는 없기 때문이다. 어간으로 일반화하는
 * 순간 그 조건이 깨진다. 오탐이 늘면 목록이 아니라 프롬프트를 손본다.
 *
 * <p><b>판정만 한다.</b> 적발 후 무엇으로 대체할지(템플릿 · 재생성 · {@code NONE})는 {@code NarrativeService}가
 * 정하며, 그래서 이 클래스는 {@code NarrativeGenerator}를 주입받지 않는다 — 템플릿 폴백은 이미 만들어 둔
 * 문장을 고르는 국소적 동작이지만 재생성은 프로바이더를 다시 부르는 다른 층위다 (spec §C-6).
 */
@Component
public class NarrativeValidator {

	private static final List<String> CAUSATION = List.of("때문에", "영향으로", "여파로", "덕분에", "로 인해", "탓에");

	private static final List<String> RECOMMENDATION = List.of("매수하세요", "매도하세요", "사야", "팔아야", "추천", "주목할", "유망",
		"비중 확대");

	private static final List<String> PREDICTION = List.of("오를 것", "내릴 것", "전망", "예상됩니다", "기대됩니다", "상승할 것", "하락할 것");

	private static final List<String> ADVICE = List.of("하세요", "했으면", "좋았을", "아쉽", "권장");

	// 가정법 어미가 다섯인 이유는 초성 때문이다. `았다면`·`었다면`·`였다면`만으로는 `기다렸다면`(렸다면)과
	// `보유했다면`(했다면)이 부분 문자열로 겹치지 않아 빠져나간다 — spec §후검증 본문과 api-contracts의 문구
	// 제약이 둘 다 "더 기다렸다면"을 대표 예로 드는데도 그랬다 (2026-08-03 발견 후 spec에 반영).
	private static final List<String> JUDGEMENT = List.of("버티", "놓치", "실수", "잘못", "다행", "기회를", "았다면", "었다면", "였다면",
		"했다면", "렸다면");

	// 뉴스 요약·브리핑에 적용하는 4줄. `판단·훈수`의 `기회를`·`실수`·`잘못`·`다행`은 기사 내용을 서술할 때
	// 자연스럽게 나오는데, 요약에는 템플릿이 없어 걸리면 기능이 통째로 사라진다. 반면 `조언·후회`는 그대로
	// 막는다 — C-004의 "조언하지 않는다"가 여기서 사라지면 안 된다 (spec §후검증).
	private static final List<String> WITHOUT_JUDGEMENT = Stream.of(CAUSATION, RECOMMENDATION, PREDICTION, ADVICE)
		.flatMap(List::stream).toList();

	// 변동 카드·매도 회고에 적용하는 5줄. 파생 사실이 늘어날수록 판단·훈수의 경계를 넘기 쉬워지므로
	// 가정법 어미(`~다면`)까지 막는다.
	private static final List<String> ALL_RULES = Stream.of(WITHOUT_JUDGEMENT, JUDGEMENT).flatMap(List::stream)
		.toList();

	/** 변동 카드·매도 회고 — §후검증 5줄 전부를 적용한다. 걸리면 호출부가 템플릿으로 대체한다. */
	public NarrativeValidationDto validateCardOrPostSell(String narrative) {
		return detect(narrative, ALL_RULES);
	}

	/** 뉴스 요약·브리핑 — `판단·훈수`를 뺀 4줄을 적용한다. 걸리면 호출부가 1회 재생성한다. */
	public NarrativeValidationDto validateSummaryOrBriefing(String narrative) {
		return detect(narrative, WITHOUT_JUDGEMENT);
	}

	// 37개 표현이 전부 리터럴 부분 문자열이라 정규식을 쓰지 않는다 — 메타문자가 하나도 없어 표현력이
	// 같고, 정규식으로 두면 어간으로 일반화(`매도하(세요)?`)하고 싶은 유혹이 구조적으로 열린다.
	private NarrativeValidationDto detect(String narrative, List<String> rules) {
		if (!StringUtils.hasText(narrative)) {
			// 서술이 없는 것은 위반이 아니다. 생성 실패는 호출부가 NarrativeGenerator의 반환값으로 먼저 가린다.
			return new NarrativeValidationDto(List.of());
		}
		List<String> detected = new ArrayList<>();
		for (String expression : rules) {
			if (narrative.contains(expression)) {
				detected.add(expression);
			}
		}
		return new NarrativeValidationDto(detected);
	}
}
