package com.finplay.api.domain.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class NewsCollectionEnvironmentVariablesTest {

	private static final List<String> NEW_ENV_VARIABLES = List.of(
		"NAVER_SEARCH_CLIENT_ID",
		"NAVER_SEARCH_CLIENT_SECRET",
		"DART_API_KEY",
		"OPENAI_API_KEY");

	private static final List<String> OAUTH_LOGIN_ENV_VARIABLES = List.of(
		"NAVER_CLIENT_ID", "NAVER_CLIENT_SECRET");

	@Test
	@DisplayName(".env.example이 신설 환경변수 4종을 값 없이 이름만으로 선언한다")
	void envExampleDeclaresEveryNewEnvironmentVariable() throws IOException {
		List<String> lines = readProjectFileLines(".env.example");

		for (String name : NEW_ENV_VARIABLES) {
			assertThat(lines)
				.as(".env.example에 %s 선언이 있어야 한다", name)
				.contains(name + "=");
		}
	}

	@Test
	@DisplayName(".env.example이 OAuth 로그인용 네이버 키 2종을 그대로 유지한다")
	void envExampleKeepsOauthLoginNaverVariablesSeparate() throws IOException {
		List<String> lines = readProjectFileLines(".env.example");

		for (String name : OAUTH_LOGIN_ENV_VARIABLES) {
			assertThat(lines)
				.as(".env.example의 %s(OAuth 로그인)는 검색 키와 별개로 남아 있어야 한다", name)
				.contains(name + "=");
		}
	}

	@Test
	@DisplayName(".env.example의 신설 환경변수 4종에 실제 값이 적혀 있지 않다")
	void envExampleCarriesNoSecretValue() throws IOException {
		List<String> lines = readProjectFileLines(".env.example");

		for (String name : NEW_ENV_VARIABLES) {
			assertThat(lines.stream().filter(line -> line.startsWith(name + "=")).toList())
				.as("%s는 값 없이 이름만 적혀야 한다", name)
				.containsExactly(name + "=");
		}
	}

	@Test
	@DisplayName("compose.deploy.yaml의 app 서비스가 env_file로 .env를 통째로 넘긴다")
	void composeDeployPassesEnvFileToApp() throws IOException {
		List<String> lines = readProjectFileLines("compose.deploy.yaml");

		assertThat(lines).contains("env_file:");
		assertThat(lines).contains("- .env");
	}

	@Test
	@DisplayName("compose.deploy.yaml의 environment 블록이 신설 환경변수 4종을 다시 적지 않는다")
	void composeDeployDoesNotOverrideNewEnvironmentVariables() throws IOException {
		List<String> lines = readProjectFileLines("compose.deploy.yaml");

		for (String name : NEW_ENV_VARIABLES) {
			assertThat(lines.stream().filter(line -> line.startsWith(name + ":")).toList())
				.as("%s를 environment 블록에 다시 적으면 env_file의 .env 값이 무시된다", name)
				.isEmpty();
		}
	}

	@Test
	@DisplayName("application.yml의 자격증명 3종 키가 신설 환경변수 이름을 참조한다")
	void applicationYmlBindsCredentialKeysToNewEnvironmentVariables() throws IOException {
		List<String> lines = readClassPathFileLines("application.yml");

		assertThat(lines).contains("client-id: ${NAVER_SEARCH_CLIENT_ID:}");
		assertThat(lines).contains("client-secret: ${NAVER_SEARCH_CLIENT_SECRET:}");
		assertThat(lines).contains("api-key: ${DART_API_KEY:}");
	}

	@Test
	@DisplayName("application.yml의 oauth.naver가 여전히 로그인용 환경변수를 참조한다")
	void applicationYmlKeepsOauthNaverBoundToLoginEnvironmentVariables() throws IOException {
		List<String> lines = readClassPathFileLines("application.yml");

		assertThat(lines).contains("client-id: ${NAVER_CLIENT_ID}");
		assertThat(lines).contains("client-secret: ${NAVER_CLIENT_SECRET}");
	}

	private static List<String> readProjectFileLines(String fileName) throws IOException {
		Path path = Path.of(fileName);
		assertThat(Files.exists(path))
			.as("프로젝트 루트에서 %s를 찾지 못했다 (실행 디렉터리: %s)",
				fileName, Path.of("").toAbsolutePath())
			.isTrue();
		return readTrimmedLines(Files.readString(path, StandardCharsets.UTF_8));
	}

	private static List<String> readClassPathFileLines(String fileName) throws IOException {
		ClassPathResource resource = new ClassPathResource(fileName);
		try (var inputStream = resource.getInputStream()) {
			return readTrimmedLines(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	private static List<String> readTrimmedLines(String content) {
		return content.lines().map(String::trim).toList();
	}
}
