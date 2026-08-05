// 테스트용 NarrativeGenerator 구현 — 응답을 미리 넣어 두고 호출 횟수·프롬프트를 기록한다 (실제 API를 호출하지 않는다).
package com.finplay.api.feedback.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

// ADR-0011: 자동 테스트는 실제 LLM을 호출하지 않는다. 출력이 비결정적이라 문자열을 단정할 수 없으므로,
// 후검증 통과 여부·narrative_source·템플릿 폴백 동작을 단정하려면 응답을 시나리오로 고정할 수 있어야 한다.
// 넣어 둔 응답이 떨어지면 실패(Optional.empty())를 반환한다 — 키 없음·타임아웃과 같은 표현이다.
public class FakeNarrativeGenerator implements NarrativeGenerator {

	private final Deque<Optional<String>> responses = new ArrayDeque<>();
	private final List<String> systemPrompts = new ArrayList<>();
	private final List<String> userPrompts = new ArrayList<>();

	// 호출 순서대로 반환할 성공 응답을 넣는다.
	public FakeNarrativeGenerator enqueue(String narrative) {
		this.responses.add(Optional.of(narrative));
		return this;
	}

	// 그 회차 호출을 실패로 만든다 (키 없음·타임아웃·HTTP 오류·빈 응답이 전부 이 하나로 수렴한다).
	public FakeNarrativeGenerator enqueueFailure() {
		this.responses.add(Optional.empty());
		return this;
	}

	// 스프링 빈으로 얹어 쓰는 통합 테스트가 테스트마다 상태를 비운다 — 싱글턴이라 응답 큐·호출 기록이 누적된다.
	public void reset() {
		this.responses.clear();
		this.systemPrompts.clear();
		this.userPrompts.clear();
	}

	public int callCount() {
		return this.userPrompts.size();
	}

	// n번째(0-base) 호출에 넘어온 사용자 프롬프트. 재생성 프롬프트에 적발 표현이 들어갔는지 단정할 때 쓴다.
	public List<String> userPrompts() {
		return List.copyOf(this.userPrompts);
	}

	public List<String> systemPrompts() {
		return List.copyOf(this.systemPrompts);
	}

	@Override
	public Optional<String> generate(String systemPrompt, String userPrompt) {
		this.systemPrompts.add(systemPrompt);
		this.userPrompts.add(userPrompt);
		Optional<String> response = this.responses.poll();
		return response == null ? Optional.empty() : response;
	}
}
