package com.finplay.api.domain.auth.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class EmailSenderProfileTest {

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
