// 뉴스·공시 수집 환경변수 4종이 .env.example·compose.deploy.yaml·application.yml에 spec 012대로 선언됐는지 검증한다.
package com.finplay.api.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

// 이슈 #167 완료 조건 "(기동) .env.example·compose.deploy.yaml에 신설 환경변수 4종"을 단정한다.
// 이름의 정본은 spec.md §외부 API 호출 상세이고, 자격증명 키 경로는 §C-7이다.
//
// 프로퍼티 바인딩 테스트(NewsCollectionPropertiesTest·NewsCollectionPropertiesIntegrationTest)는 yml 키 경로까지만
// 본다. 그 키를 채울 환경변수 이름이 빠지거나 로그인용 이름과 겹쳐도 바인딩은 빈 값으로 통과하므로 여기서 따로 본다.
class NewsCollectionEnvironmentVariablesTest {

	// §외부 API 호출 상세 — 이번 이슈가 신설하는 환경변수 4종
	private static final List<String> NEW_ENV_VARIABLES = List.of(
		"NAVER_SEARCH_CLIENT_ID",
		"NAVER_SEARCH_CLIENT_SECRET",
		"DART_API_KEY",
		"OPENAI_API_KEY");

	// §외부 API 호출 상세 — 네이버 OAuth 로그인이 이미 쓰고 있어 재사용하면 안 되는 이름
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

	// 검색 키가 로그인 키를 대체해 버리면(이름 재사용·기존 항목 치환) 검색과 로그인 중 하나가 조용히 깨진다.
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

	// conventions.md 시크릿 규칙 — .env.example에는 이름만 적고 실제 값을 적지 않는다.
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

	// compose.deploy.yaml의 app 서비스는 .env를 통째로 넘긴다. 이 경로가 4종의 유일한 전달 통로다.
	@Test
	@DisplayName("compose.deploy.yaml의 app 서비스가 env_file로 .env를 통째로 넘긴다")
	void composeDeployPassesEnvFileToApp() throws IOException {
		List<String> lines = readProjectFileLines("compose.deploy.yaml");

		assertThat(lines).contains("env_file:");
		assertThat(lines).contains("- .env");
	}

	// environment 블록은 env_file보다 우선한다. 4종 중 하나라도 여기에 이름이 다시 적히면 .env 값이 무시돼
	// 배포에서만 키가 비는데, 로컬·테스트는 키 없이도 정상이라 끝까지 드러나지 않는다.
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

	// §C-7의 키 경로가 §외부 API 호출 상세의 환경변수 이름으로 채워지는지 — 두 문서를 잇는 지점이다.
	@Test
	@DisplayName("application.yml의 자격증명 3종 키가 신설 환경변수 이름을 참조한다")
	void applicationYmlBindsCredentialKeysToNewEnvironmentVariables() throws IOException {
		List<String> lines = readClassPathFileLines("application.yml");

		assertThat(lines).contains("client-id: ${NAVER_SEARCH_CLIENT_ID:}");
		assertThat(lines).contains("client-secret: ${NAVER_SEARCH_CLIENT_SECRET:}");
		assertThat(lines).contains("api-key: ${DART_API_KEY:}");
	}

	// 로그인 쪽 참조가 그대로 남아 있어야 "별개 환경변수"가 성립한다.
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
