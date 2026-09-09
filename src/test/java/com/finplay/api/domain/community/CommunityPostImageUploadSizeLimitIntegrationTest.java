package com.finplay.api.domain.community;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.community.repository.CommunityPostImageRepository;
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
		jdbcTemplate.update("delete from post_comments where parent_comment_id is not null");
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
