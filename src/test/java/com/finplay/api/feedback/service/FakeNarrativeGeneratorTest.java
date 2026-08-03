// 뒤 이슈(#4~#6)의 테스트가 딛고 설 FakeNarrativeGenerator의 계약을 고정하는 단위 테스트.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Fake가 조용히 틀리면 그 위에 쌓는 NarrativeService 테스트(호출 횟수·재생성 프롬프트 단정)가 통째로 거짓이 된다.
class FakeNarrativeGeneratorTest {

	@Test
	@DisplayName("넣어 둔 응답을 호출 순서대로 반환하고 프롬프트를 그 순서로 기록한다")
	void returnsEnqueuedResponsesInOrderAndRecordsPrompts() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator()
			.enqueue("첫 번째 서술")
			.enqueue("두 번째 서술");

		assertThat(generator.generate("시스템1", "사용자1")).contains("첫 번째 서술");
		assertThat(generator.generate("시스템2", "사용자2")).contains("두 번째 서술");

		assertThat(generator.callCount()).isEqualTo(2);
		assertThat(generator.systemPrompts()).containsExactly("시스템1", "시스템2");
		assertThat(generator.userPrompts()).containsExactly("사용자1", "사용자2");
	}

	@Test
	@DisplayName("enqueueFailure를 넣은 회차는 실패를 반환한다 — 키 없음·타임아웃과 같은 표현이다")
	void returnsFailureForEnqueuedFailure() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator()
			.enqueueFailure()
			.enqueue("폴백 뒤 서술");

		assertThat(generator.generate("시스템", "사용자")).isEmpty();
		assertThat(generator.generate("시스템", "사용자")).contains("폴백 뒤 서술");
	}

	@Test
	@DisplayName("넣어 둔 응답이 떨어져도 예외 없이 실패를 반환하고 호출은 계속 기록된다")
	void returnsFailureWhenResponsesAreExhausted() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator();

		Optional<String> result = generator.generate("시스템", "사용자");

		assertThat(result).isEmpty();
		// 상한 초과 호출을 단정하려면(§완료 조건 "max-regeneration + 1을 넘지 않는다") 응답이 떨어진 뒤에도 세어야 한다.
		assertThat(generator.callCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("기록된 프롬프트 목록은 밖에서 바꿀 수 없는 복사본이다")
	void exposesPromptsAsImmutableCopies() {
		FakeNarrativeGenerator generator = new FakeNarrativeGenerator().enqueue("서술");
		generator.generate("시스템", "사용자");

		assertThat(generator.userPrompts()).isUnmodifiable();
		assertThat(generator.systemPrompts()).isUnmodifiable();
	}
}
