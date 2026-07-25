// FakeEmailSender가 발송 내역을 메모리에 정확히 기록·누적·초기화하는지 검증하는 단위 테스트
package com.finplay.api.auth.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.auth.email.FakeEmailSender.SentEmail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FakeEmailSenderTest {

	private FakeEmailSender emailSender;

	@BeforeEach
	void setUp() {
		emailSender = new FakeEmailSender();
	}

	@Test
	@DisplayName("sendVerificationCode 호출 시 대상 이메일과 코드가 마지막 발송 내역으로 기록된다")
	void sendVerificationCodeRecordsRecipientAndCode() {
		emailSender.sendVerificationCode("user@example.com", "123456");

		SentEmail last = emailSender.getLastSentEmail();
		assertThat(last).isNotNull();
		assertThat(last.toEmail()).isEqualTo("user@example.com");
		assertThat(last.code()).isEqualTo("123456");
	}

	@Test
	@DisplayName("여러 건 발송하면 순서대로 누적되고 getLastSentEmail은 가장 최근 건을 반환한다")
	void multipleSendsAccumulateInOrder() {
		emailSender.sendVerificationCode("first@example.com", "111111");
		emailSender.sendVerificationCode("second@example.com", "222222");

		assertThat(emailSender.getSentEmails())
			.containsExactly(
				new SentEmail("first@example.com", "111111"), new SentEmail("second@example.com", "222222"));
		assertThat(emailSender.getLastSentEmail()).isEqualTo(new SentEmail("second@example.com", "222222"));
	}

	@Test
	@DisplayName("발송 이력이 없으면 getLastSentEmail은 null, getSentEmails는 빈 리스트를 반환한다")
	void returnsEmptyStateWhenNothingSent() {
		assertThat(emailSender.getLastSentEmail()).isNull();
		assertThat(emailSender.getSentEmails()).isEmpty();
	}

	@Test
	@DisplayName("clear 호출 시 발송 이력이 모두 비워진다")
	void clearRemovesAllSentEmails() {
		emailSender.sendVerificationCode("user@example.com", "123456");
		emailSender.clear();

		assertThat(emailSender.getSentEmails()).isEmpty();
		assertThat(emailSender.getLastSentEmail()).isNull();
	}

	@Test
	@DisplayName("getSentEmails가 반환한 리스트는 불변이라 외부에서 내부 상태를 오염시킬 수 없다")
	void returnedListIsImmutableSnapshot() {
		emailSender.sendVerificationCode("user@example.com", "123456");

		var snapshot = emailSender.getSentEmails();

		assertThat(snapshot).hasSize(1);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot.add(new SentEmail("x@x.com", "000000")))
			.isInstanceOf(UnsupportedOperationException.class);
	}
}
