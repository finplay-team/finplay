// 비-prod 프로필에서 RESEND 키 없이도 EmailSender 빈이 FakeEmailSender로 주입되는지 보증하는 회귀 테스트
package com.finplay.api.auth.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class EmailSenderProfileTest {

	// 두 구현을 모두 등록하되 프로필만 다르게 두어, 프로필 분기가 실제로 어느 빈을 선택하는지 검증한다.
	// ResendEmailSender(prod)는 RestClient.Builder를 필요로 하므로 최소 빈을 함께 등록한다.
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(RestClient.Builder.class, RestClient::builder)
		.withUserConfiguration(FakeEmailSender.class, ResendEmailSender.class);

	@Test
	@DisplayName("기본(비-prod) 프로필에서는 RESEND_API_KEY·EMAIL_FROM 없이도 EmailSender가 FakeEmailSender로 주입된다")
	void defaultProfileWiresFakeEmailSenderWithoutResendKeys() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(EmailSender.class);
			assertThat(context.getBean(EmailSender.class)).isInstanceOf(FakeEmailSender.class);
			assertThat(context).doesNotHaveBean(ResendEmailSender.class);
		});
	}

	@Test
	@DisplayName("prod 프로필에서는 FakeEmailSender가 제외되어 로컬용 구현이 운영에 새어 나가지 않는다")
	void prodProfileExcludesFakeEmailSender() {
		contextRunner
			.withPropertyValues("resend.api-key=test-key", "email.from=no-reply@finplay.com")
			.withSystemProperties("spring.profiles.active=prod")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(FakeEmailSender.class);
				assertThat(context.getBean(EmailSender.class)).isInstanceOf(ResendEmailSender.class);
			});
	}
}
