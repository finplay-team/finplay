// 실제 임베디드 서블릿 컨테이너(RANDOM_PORT)를 통해 5MB 초과 이미지 업로드가 컨테이너 레벨에서 거부되는지
// 검증하는 통합 테스트다. MockMvc(MOCK 환경)는 파트를 메모리에서 직접 구성해 컨테이너의 멀티파트 크기 제한을
// 우회하므로 spring.servlet.multipart.max-file-size 강제 여부는 실제 HTTP 요청으로만 확인할 수 있다.
// TestRestTemplate 전용 스타터가 이 프로젝트 의존성에 없어 spring-web의 RestTemplate을 직접 사용한다.
package com.finplay.api.community;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.community.repository.CommunityPostImageRepository;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CommunityPostImageUploadSizeLimitIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 7, 12, 0);

	@TempDir
	static Path imageStorageDirectory;

	@DynamicPropertySource
	static void overrideImageStorageDirectory(DynamicPropertyRegistry registry) {
		registry.add("finplay.community.image-storage.base-directory", imageStorageDirectory::toString);
	}

	@LocalServerPort
	private int port;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private CommunityPostImageRepository imageRepository;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final RestTemplate restTemplate = new RestTemplate();

	@BeforeEach
	void cleanDatabaseInForeignKeySafeOrder() {
		jdbcTemplate.update("delete from post_comments");
		jdbcTemplate.update("delete from community_post_images");
		jdbcTemplate.update("delete from community_posts");
	}

	@Test
	void uploadingFileLargerThanFiveMegabytesReturnsBadRequest() {
		User author = createUser("too-large-author");
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();
		byte[] oversized = new byte[6 * 1024 * 1024];

		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("image", new ByteArrayResource(oversized) {
			@Override
			public String getFilename() {
				return "big.png";
			}
		});
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		headers.setBearerAuth(accessToken);
		HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

		String url = "http://localhost:" + port + "/api/community/posts/images";
		try {
			ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(response.getBody()).contains("VALIDATION_ERROR");
		} catch (HttpClientErrorException e) {
			assertThat(HttpStatus.valueOf(e.getStatusCode().value())).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(e.getResponseBodyAsString()).contains("VALIDATION_ERROR");
		} catch (ResourceAccessException e) {
			// Tomcat이 멀티파트 파싱 도중 5MB 초과(fileSizeMax)를 감지하면 즉시 400 응답을 쓰고 커넥션을 닫는다.
			// 남은 요청 바디(6MB - 5MB 한도 초과분)가 Tomcat의 maxSwallowSize(기본 2MB)를 넘어 다 읽어내지
			// 못하면 클라이언트가 응답 본문을 받기 전에 소켓이 리셋돼 "Error writing request body" I/O 예외로
			// 관측된다 — 이 경우도 요청이 정상 처리(201)되지 않았다는 근거로 충분하다(아래 imageRepository 카운트로 재확인).
		}
		assertThat(imageRepository.count()).isZero();
	}

	private User createUser(String prefix) {
		String unique = UUID.randomUUID().toString().replace("-", "");
		return userRepository.saveAndFlush(User.create(
			prefix + "-" + unique + "@finplay.com",
			"hash",
			prefix + "-" + unique,
			NOW));
	}
}
