package com.finplay.api.domain.auth.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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

class VerificationCodePolicyTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 3, 10, 30, 0);
	private static final List<LocalDateTime> ALL_WINDOWS = List.of(
		NOW.minusSeconds(VerificationCodePolicy.RESEND_INTERVAL_SECONDS),
		NOW.minusHours(1),
		NOW.minusDays(1));

	private final VerificationCodePolicy policy = new VerificationCodePolicy();

	@Test
	@DisplayName("정책 상수 5개는 PRD에 적힌 값에서 한 칸도 움직이지 않는다")
	void policyConstantsArePinnedToTheValuesWrittenInThePrd() {
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
			assertThat(code).isEqualTo(String.format("%06d", Integer.parseInt(code)));
		});
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

		assertThat(counter.arguments()).containsExactlyElementsOf(ALL_WINDOWS.subList(0, expectedQueries));
	}

	@Test
	@DisplayName("첫 창만 > 0이고 나머지 둘은 >= LIMIT다 — 이 비대칭이 재발송 간격과 횟수 한도를 가른다")
	void firstWindowRejectsOnASingleRowWhileTheOthersRejectOnlyAtTheirLimit() {
		assertThatThrownBy(() -> policy.checkSendRateLimit(NOW, new RecordingCounter(1, 0, 0)))
			.isInstanceOf(BusinessException.class);

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
		RecordingCounter counter = new RecordingCounter(0, 0, 0);

		policy.checkSendRateLimit(NOW, counter);

		assertThat(counter.arguments()).containsExactly(
			NOW.minusSeconds(VerificationCodePolicy.RESEND_INTERVAL_SECONDS),
			NOW.minusHours(1),
			NOW.minusDays(1));
	}

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
		boolean anyPaddedCode = false;
		for (int i = 0; i < 2_000 && !anyPaddedCode; i++) {
			anyPaddedCode = policy.generateCode().startsWith("0");
		}

		assertThat(anyPaddedCode).as("앞자리가 0인 인증번호가 한 건도 발급되지 않았다").isTrue();
	}
}
