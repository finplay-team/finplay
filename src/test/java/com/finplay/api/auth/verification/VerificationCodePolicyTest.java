// 공통화된 인증번호 정책 상수·생성 형식·시도 한도·발송 제한 경계를 고정하는 단위 테스트 (#121 Task 3·4)
package com.finplay.api.auth.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// 공통화로 정책값이 한곳에 모이면서 상수 한 줄을 고치면 가입 인증·이메일 변경·비밀번호 재설정 세 도메인의 정책이
// 동시에 바뀐다. 그 사고를 여기서 잡는다 — 이 파일이 리팩터링의 핵심 방어선이다.
class VerificationCodePolicyTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 3, 10, 30, 0);
	// 발송 제한이 조회해야 하는 창의 기준 시각을 판정 순서대로 나열한 것이다.
	private static final List<LocalDateTime> ALL_WINDOWS = List.of(
		NOW.minusSeconds(VerificationCodePolicy.RESEND_INTERVAL_SECONDS),
		NOW.minusHours(1),
		NOW.minusDays(1));

	private final VerificationCodePolicy policy = new VerificationCodePolicy();

	@Test
	@DisplayName("정책 상수 5개는 PRD에 적힌 값에서 한 칸도 움직이지 않는다")
	void policyConstantsArePinnedToTheValuesWrittenInThePrd() {
		// 고정값 회귀 — PRD AUTH-004(249·251·255행)·AUTH-005(278행)·AUTH-006(297-298행)의 서술과 1:1로 대응한다.
		// 여기가 깨지면 정책을 바꾼 것이고, 세 도메인의 응답·제한이 함께 바뀐다. 기대값을 고쳐서 통과시키면 안 된다.
		assertThat(VerificationCodePolicy.CODE_TTL_MINUTES).as("인증번호 유효 시간(분)").isEqualTo(5);
		assertThat(VerificationCodePolicy.RESEND_INTERVAL_SECONDS).as("재발송 최소 간격(초)").isEqualTo(60);
		assertThat(VerificationCodePolicy.HOURLY_LIMIT).as("1시간 발송 한도(회)").isEqualTo(5);
		assertThat(VerificationCodePolicy.DAILY_LIMIT).as("하루 발송 한도(회)").isEqualTo(10);
		assertThat(VerificationCodePolicy.MAX_VERIFICATION_ATTEMPTS).as("인증번호 최대 시도(회)").isEqualTo(5);
	}

	@Test
	@DisplayName("expiresAt은 기준 시각에 만료 시간만 더하고 기준 시각 자체는 건드리지 않는다")
	void expiresAtAddsExactlyTheTtlToTheGivenInstant() {
		LocalDateTime now = LocalDateTime.of(2026, 8, 3, 10, 30, 0);

		LocalDateTime expiresAt = policy.expiresAt(now);

		assertThat(expiresAt).isEqualTo(LocalDateTime.of(2026, 8, 3, 10, 35, 0));
		assertThat(expiresAt).isEqualTo(now.plusMinutes(VerificationCodePolicy.CODE_TTL_MINUTES));
		assertThat(now).as("입력 시각은 불변이어야 한다").isEqualTo(LocalDateTime.of(2026, 8, 3, 10, 30, 0));
	}

	@ParameterizedTest(name = "attemptCount={0} → 한도 도달 {1}")
	@CsvSource({"0, false", "1, false", "4, false", "5, true", "6, true", "10, true"})
	@DisplayName("시도 한도 판정은 >= 경계다 — 5회에 도달한 상태에서 이미 한도다")
	void attemptLimitIsReachedOnceTheCountMeetsTheMaximum(int attemptCount, boolean reached) {
		// 이 경계가 >로 느슨해지면 6번째 요청이 429가 아니라 코드를 한 번 더 대조해 보게 되어 실질 한도가 6회가 된다.
		// 반대로 <=로 조여지면 5번째 오답이 429가 되어 기존 400 계약이 깨진다 (#116 D1).
		assertThat(policy.isAttemptLimitReached(attemptCount)).isEqualTo(reached);
	}

	@Test
	@DisplayName("generateCode는 항상 숫자 6자리이고 앞자리 0을 잘라내지 않는다")
	void generateCodeAlwaysProducesSixDigitsIncludingLeadingZeros() {
		List<String> codes = new ArrayList<>();
		for (int i = 0; i < 2_000; i++) {
			codes.add(policy.generateCode());
		}

		assertThat(codes).allSatisfy(code -> {
			assertThat(code).matches("\\d{6}");
			assertThat(Integer.parseInt(code)).isBetween(0, 999_999);
			// 앞자리 0이 잘리면 이 단정이 깨진다 — 100000 미만 값도 6자리로 채워져야 한다.
			assertThat(code).isEqualTo(String.format("%06d", Integer.parseInt(code)));
		});
		// 고정값을 돌려주는 구현으로 바뀌면(예: 상수 반환) 여기서 잡힌다.
		// 1,000,000 공간에서 2,000회를 뽑으므로 우연한 중복은 몇 건 생길 수 있다 — 개수 자체는 단정하지 않는다.
		assertThat(new HashSet<>(codes)).hasSizeGreaterThan(1_000);
	}

	@ParameterizedTest(name = "60초 {0}건·1시간 {1}건·하루 {2}건 → 거부 {3}, 조회 {4}회")
	@CsvSource({
		"0, 4, 9, false, 3",
		"1, 1, 1, true, 1",
		"0, 5, 5, true, 2",
		"0, 4, 10, true, 3"})
	@DisplayName("발송 제한은 60초 → 1시간 → 하루 순으로 판정하고, 걸린 창 뒤쪽은 조회조차 하지 않는다")
	void sendRateLimitRejectsAtEachWindowBoundaryAndShortCircuitsAfterward(
		long within60Seconds, long withinHour, long withinDay, boolean rejected, int expectedQueries) {

		RecordingCounter counter = new RecordingCounter(within60Seconds, withinHour, withinDay);

		if (rejected) {
			assertThatThrownBy(() -> policy.checkSendRateLimit(NOW, counter))
				.isInstanceOf(BusinessException.class)
				.extracting(ex -> ((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
		} else {
			assertThatCode(() -> policy.checkSendRateLimit(NOW, counter)).doesNotThrowAnyException();
		}

		// 조회한 창의 순서와 개수를 함께 고정한다 — 앞 창에서 거부됐는데 뒤 창까지 조회하면 여기서 잡힌다.
		// 판정 순서가 바뀌어도(예: 하루 창을 먼저 보면) 인자 순서가 어긋나 깨진다.
		assertThat(counter.arguments()).containsExactlyElementsOf(ALL_WINDOWS.subList(0, expectedQueries));
	}

	@Test
	@DisplayName("첫 창만 > 0이고 나머지 둘은 >= LIMIT다 — 이 비대칭이 재발송 간격과 횟수 한도를 가른다")
	void firstWindowRejectsOnASingleRowWhileTheOthersRejectOnlyAtTheirLimit() {
		// 60초 창은 "한 건이라도 있으면 거부"하는 재발송 간격 규칙이라 1건에서 이미 429다.
		// 여기가 >= HOURLY_LIMIT 같은 모양으로 "정리"되면 60초 안에 4번까지 재발송할 수 있게 된다.
		assertThatThrownBy(() -> policy.checkSendRateLimit(NOW, new RecordingCounter(1, 0, 0)))
			.isInstanceOf(BusinessException.class);

		// 1시간·하루 창은 "N회째부터 거부"하는 횟수 한도라 한도 바로 아래(4·9)는 통과하고 한도(5·10)에서 거부다.
		// 여기가 > LIMIT으로 느슨해지면 실제 한도가 6회·11회가 된다.
		assertThatCode(() -> policy.checkSendRateLimit(NOW, new RecordingCounter(0, 4, 9)))
			.doesNotThrowAnyException();
		assertThatThrownBy(() -> policy.checkSendRateLimit(NOW, new RecordingCounter(0, 5, 9)))
			.isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> policy.checkSendRateLimit(NOW, new RecordingCounter(0, 4, 10)))
			.isInstanceOf(BusinessException.class);
	}

	@Test
	@DisplayName("각 창의 조회 기준 시각은 60초 전·1시간 전·하루 전이다")
	void eachWindowIsQueriedWithItsOwnStartInstant() {
		// 창 폭이 바뀌면(예: minusHours(2)) 상수는 그대로여도 실제 제한 범위가 달라진다.
		RecordingCounter counter = new RecordingCounter(0, 0, 0);

		policy.checkSendRateLimit(NOW, counter);

		assertThat(counter.arguments()).containsExactly(
			NOW.minusSeconds(VerificationCodePolicy.RESEND_INTERVAL_SECONDS),
			NOW.minusHours(1),
			NOW.minusDays(1));
	}

	// 조회한 창의 기준 시각을 순서대로 기록하고, 창별로 정해둔 발송 횟수를 돌려주는 스파이.
	// 값을 "호출 순서"가 아니라 "창"에 매어 두므로, 판정 순서가 바뀌면 기록된 인자 순서로 드러난다.
	// 정의되지 않은 창을 조회하면(창 폭이 바뀌면) 그 자리에서 실패한다.
	private static final class RecordingCounter implements ToLongFunction<LocalDateTime> {

		private final Map<LocalDateTime, Long> countsByWindowStart;
		private final List<LocalDateTime> arguments = new ArrayList<>();

		private RecordingCounter(long within60Seconds, long withinHour, long withinDay) {
			this.countsByWindowStart = Map.of(
				NOW.minusSeconds(VerificationCodePolicy.RESEND_INTERVAL_SECONDS), within60Seconds,
				NOW.minusHours(1), withinHour,
				NOW.minusDays(1), withinDay);
		}

		@Override
		public long applyAsLong(LocalDateTime since) {
			arguments.add(since);
			Long count = countsByWindowStart.get(since);
			assertThat(count).as("정의되지 않은 창을 조회했다: %s", since).isNotNull();
			return count;
		}

		private List<LocalDateTime> arguments() {
			return List.copyOf(arguments);
		}
	}

	@Test
	@DisplayName("100000 미만 인증번호도 실제로 발급되어 앞자리 0 채움 경로가 살아 있다")
	void codesBelowOneHundredThousandAreActuallyIssuedSoZeroPaddingIsExercised() {
		// 난수 상한이 1_000_000이면 한 번에 10% 확률이라 2000회 중 한 건도 안 나올 확률은 0.9^2000(≈10^-91)이다.
		// 상한을 900000 시작 같은 값으로 바꿔 앞자리 0이 사라지면 이 단정이 깨진다.
		boolean anyPaddedCode = false;
		for (int i = 0; i < 2_000 && !anyPaddedCode; i++) {
			anyPaddedCode = policy.generateCode().startsWith("0");
		}

		assertThat(anyPaddedCode).as("앞자리가 0인 인증번호가 한 건도 발급되지 않았다").isTrue();
	}
}
