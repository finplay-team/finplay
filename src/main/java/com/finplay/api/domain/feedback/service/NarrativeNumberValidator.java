// LLM이 만든 서술의 수치를 프롬프트가 준 수치 집합과 대조하는 후검증기 — 판정만 하고 대체는 하지 않는다.
package com.finplay.api.domain.feedback.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 표현 대조({@link NarrativeValidator})가 못 보는 축을 맡는다. 금지 표현 목록은 글자를 보므로
 * {@code -15.6%}와 {@code -1.56%}를 이웃으로 읽는데 의미로는 열 배 차이라, 목록에 넣을 수 있는 문자열이
 * 애초에 없다. 그래서 목록을 넓히는 대신 <b>대조할 기준값</b>을 준다 (spec 053 §개요).
 *
 * <p><b>기존 검증기와 한 클래스로 합치지 않는다</b> (053 plan §결정 A). 입력이 {@code (서술, 프롬프트)}로
 * 하나 더 많고, 무엇보다 {@code NarrativeValidator}의 "37개가 전부 리터럴이라 정규식을 쓰지 않는다"는
 * 전제와 정반대다 — 숫자 대조는 정규식이 있어야 성립한다. 두 전제를 한 클래스에 두면 "정규식이 이미
 * 있으니 표현도 정규식으로" 하는 길이 열리고, 그것이 053이 피하려는 방향이다.
 *
 * <p><b>허용 집합은 사용자 프롬프트 문자열에 등장하는 모든 수다.</b> DTO가 넘긴 값만 따로 모으지 않고
 * 화이트리스트도 두지 않는다 (spec §결정 2) — 예외 목록은 커질수록 다시 "표현 목록으로 하는 검증"이 되고,
 * 프롬프트를 고치면 허용 집합이 저절로 따라온다는 것이 이 선택의 값이다. 대가로 지시문의 작은 수
 * ({@code 3~4문장}의 3·4), 시각에서 갈라진 수(콜론을 자르므로 {@code 09:30}은 09와 30), 사용자가 일기에
 * 적은 수가 함께 허용된다. 그중 콜론 분리는 필수다 — 하나로 묶으면 "09시 30분에 매수했습니다"라는
 * <b>정상 서술이 전부 위반</b>이 되어 매도 회고가 통째로 템플릿으로 떨어진다.
 *
 * <p><b>판정만 한다.</b> 적발 후 무엇으로 대체할지는 {@code NarrativeService}가 정하며, 이 축은 매도
 * 회고에만 걸린다 — 변동 카드 프롬프트에는 구간 길이(분)가 없어 카드 템플릿의 {@code 5분간}이 설계상
 * 위반이 되고, 그러면 폴백 경로 자체가 성립하지 않는다 (053 plan §결정 B).
 */
@Component
public class NarrativeNumberValidator {

	// 천 단위 구분자는 `,\d{3}` 반복으로만 붙인다 — `[\d,]*`로 느슨하게 잡으면 `70,000, 10`에서 꼬리
	// 쉼표까지 토큰에 딸려 들어간다. 소수점은 뒤에 숫자가 있을 때만 포함해 문장 끝 마침표를 소수점으로
	// 읽지 않는다. `%`·`원`·`분` 같은 단위와 조사는 토큰에 넣지 않는다 — spec §결정 1이 "수(數)만 본다"다.
	private static final Pattern NUMBER = Pattern.compile("[+\\-]?\\d+(?:,\\d{3})*(?:\\.\\d+)?");

	/**
	 * 서술의 수치가 전부 프롬프트에서 온 것인지 본다. 걸리면 호출부가 템플릿으로 대체한다.
	 *
	 * @param narrative 검사 대상 서술. {@code null}·공백은 <b>위반이 아니다</b> — 생성 실패는 호출부가
	 *     {@code NarrativeGenerator}의 반환값으로 먼저 가린다 ({@code NarrativeValidator.detect}와 같은 처리)
	 * @param prompt 허용 집합의 출처. {@code NarrativePromptBuilder.postSellPrompt}가 만든 사용자 프롬프트
	 *     문자열 그대로다. 비어 있으면 서술의 모든 수치가 위반이 된다
	 */
	public NarrativeValidationDto validate(String narrative, String prompt) {
		if (!StringUtils.hasText(narrative)) {
			return new NarrativeValidationDto(List.of());
		}
		List<BigDecimal> allowed = numbersIn(prompt);

		// 적발 목록에는 서술에서 뽑은 원문 토큰을 등장 순서대로, 중복 없이 담는다 — 로그 `적발={}`에서
		// 어떤 수가 문제였는지 바로 읽혀야 한다. 중복 판정은 정규화 값이 아니라 토큰 글자로 한다
		// (`-21.7`과 `21.7`은 다른 토큰이다).
		List<String> detected = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		Matcher matcher = NUMBER.matcher(narrative);
		while (matcher.find()) {
			String token = matcher.group();
			if (seen.add(token) && !sourced(token, allowed)) {
				detected.add(token);
			}
		}
		return new NarrativeValidationDto(detected);
	}

	private List<BigDecimal> numbersIn(String prompt) {
		if (!StringUtils.hasText(prompt)) {
			return List.of();
		}
		List<BigDecimal> numbers = new ArrayList<>();
		Matcher matcher = NUMBER.matcher(prompt);
		while (matcher.find()) {
			numbers.add(normalize(matcher.group()));
		}
		return numbers;
	}

	/**
	 * 부호는 <b>서술이 기호를 붙였을 때만</b> 본다 (spec §결정 1).
	 *
	 * <p>기호가 없으면 절댓값만 맞으면 통과한다 — {@code -15,207원}을 주고 "15,207원 손실"이라 쓴 문장은
	 * 부호를 말로 옮긴 것뿐이다. 기호를 붙였으면 방향까지 같아야 하므로 "+15,207원"은 위반이다.
	 *
	 * <p><b>한계.</b> "2.17% 이익"처럼 부호를 말로만 뒤집은 문장은 이 축으로 잡지 못한다. 그것은 숫자
	 * 환각이 아니라 방향을 뒤집은 서술 오류이고, 잡으려면 "손실·이익"의 표현 목록이 다시 필요해져
	 * 053이 벗어나려는 자리로 되돌아간다.
	 */
	private boolean sourced(String token, List<BigDecimal> allowed) {
		BigDecimal value = normalize(token);
		boolean explicitSign = token.startsWith("+") || token.startsWith("-");
		for (BigDecimal candidate : allowed) {
			boolean same = explicitSign
				? candidate.compareTo(value) == 0
				: candidate.abs().compareTo(value.abs()) == 0;
			if (same) {
				return true;
			}
		}
		return false;
	}

	// 쉼표만 지우고 BigDecimal로 만든 뒤 compareTo로 비교한다 — `70,000`과 `70000`, `2.17`과 `2.170`이
	// 같은 값이 되어 표기 차이가 저절로 흡수된다. equals는 scale까지 보므로 쓰지 않는다.
	private BigDecimal normalize(String token) {
		return new BigDecimal(token.replace(",", ""));
	}
}
