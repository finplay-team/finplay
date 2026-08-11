// 실제 인증 필터와 MySQL, 로컬 파일시스템 저장을 연결해 게시물 첨부 이미지(COM-006) 핵심 시나리오를 검증하는 통합 테스트다.
package com.finplay.api.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.community.repository.CommunityPostImageRepository;
import com.finplay.api.community.repository.CommunityPostRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CommunityPostImageIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 7, 12, 0);

	// 실제 프로젝트 디렉터리(./data/community-images)를 오염시키지 않도록 각 이미지 저장 위치를 임시 디렉터리로 덮어쓴다.
	@TempDir
	static Path imageStorageDirectory;

	@DynamicPropertySource
	static void overrideImageStorageDirectory(DynamicPropertyRegistry registry) {
		registry.add("finplay.community.image-storage.base-directory", imageStorageDirectory::toString);
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private CommunityPostRepository postRepository;

	@Autowired
	private CommunityPostImageRepository imageRepository;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void cleanDatabaseInForeignKeySafeOrder() {
		// V31: parent_comment_id FK가 ON DELETE RESTRICT라 단일 "delete from post_comments"는
		// 다른 테스트 컨텍스트가 남긴 부모+자식이 섞여 있으면 행 처리 순서 미보장으로 실패할 수 있다(이슈 #277).
		jdbcTemplate.update("delete from post_comments where parent_comment_id is not null");
		jdbcTemplate.update("delete from post_comments");
		jdbcTemplate.update("delete from community_post_images");
		jdbcTemplate.update("delete from community_posts");
	}

	@AfterEach
	void cleanDatabaseAndPhysicalFiles() throws IOException {
		jdbcTemplate.update("delete from post_comments where parent_comment_id is not null");
		jdbcTemplate.update("delete from post_comments");
		jdbcTemplate.update("delete from community_post_images");
		jdbcTemplate.update("delete from community_posts");
		try (var files = Files.list(imageStorageDirectory)) {
			for (Path file : files.toList()) {
				Files.deleteIfExists(file);
			}
		}
	}

	@Test
	void uploadImageThenCreatePostIncludesImageUrlAndFileIsDownloadable() throws Exception {
		User author = createUser("image-author");
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();
		byte[] imageBytes = "fake-png-bytes".getBytes();
		MockMultipartFile image = new MockMultipartFile("image", "photo.png", "image/png", imageBytes);

		String uploadResponseBody = mockMvc.perform(multipart("/api/community/posts/images")
			.file(image)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		Long imageId = objectMapper.readTree(uploadResponseBody).get("imageId").asLong();
		String imageUrl = objectMapper.readTree(uploadResponseBody).get("imageUrl").asText();
		assertThat(imageUrl).isEqualTo("/api/community/posts/images/" + imageId + "/file");

		String createResponseBody = mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"image post","content":"has image","imageId":%d}
				""".formatted(imageId)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		var createJson = objectMapper.readTree(createResponseBody);
		Long postId = createJson.get("postId").asLong();

		String detailResponseBody = mockMvc.perform(get("/api/community/posts/{postId}", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		var detailJson = objectMapper.readTree(detailResponseBody);

		byte[] downloaded = mockMvc.perform(get(imageUrl)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsByteArray();

		// SoftAssertions로 묶는다 — 생성 응답의 imageId 필드가 다시 비어지는 회귀가 있어도(CommunityPost.attachImage
		// 양방향 동기화 누락, 4de53f3에서 수정됨), 단건 조회·다운로드까지 이어지는 나머지 시나리오 검증이
		// 모두 실행되고 각각 별도로 보고되도록 한다.
		org.assertj.core.api.SoftAssertions softly = new org.assertj.core.api.SoftAssertions();
		softly.assertThat(createJson.hasNonNull("imageId") ? createJson.get("imageId").asLong() : null)
			.as("POST /api/community/posts 응답의 imageId (plan.md COM-006 API 설계 표 3번째 행 계약)")
			.isEqualTo(imageId);
		softly.assertThat(detailJson.get("imageId").asLong())
			.as("GET /api/community/posts/{postId} 응답의 imageId")
			.isEqualTo(imageId);
		softly.assertThat(detailJson.get("imageUrl").asText())
			.as("GET /api/community/posts/{postId} 응답의 imageUrl")
			.isEqualTo(imageUrl);
		softly.assertThat(downloaded)
			.as("다운로드 엔드포인트로 받은 바이트가 업로드한 원본과 일치")
			.isEqualTo(imageBytes);
		softly.assertAll();
	}

	@Test
	void uploadImageRejectsDisallowedFormatWithBadRequest() throws Exception {
		User author = createUser("bad-format-author");
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();
		MockMultipartFile file = new MockMultipartFile(
			"image", "notes.txt", "text/plain", "not an image".getBytes());

		mockMvc.perform(multipart("/api/community/posts/images")
			.file(file)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		assertThat(imageRepository.count()).isZero();
	}

	// 5MB 초과 업로드(MaxUploadSizeExceededException 매핑) 검증은 실제 서블릿 컨테이너의 멀티파트 크기 제한을
	// 거쳐야 한다 — MockMvc는 파트를 메모리에서 직접 구성해 컨테이너 레벨 크기 검증을 우회하므로 이 클래스(MOCK 환경)로는
	// 재현되지 않는다(아래 CommunityPostImageUploadSizeLimitIntegrationTest에서 실제 포트로 검증, docs/agent-mistakes.md 후보 기록 대상).

	@Test
	void deletingPostWithImageRemovesDatabaseRowAndPhysicalFile() throws Exception {
		User author = createUser("delete-author");
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();
		MockMultipartFile image = new MockMultipartFile("image", "photo.png", "image/png", "bytes".getBytes());

		String uploadResponseBody = mockMvc.perform(multipart("/api/community/posts/images")
			.file(image)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		Long imageId = objectMapper.readTree(uploadResponseBody).get("imageId").asLong();
		String storedFilename = imageRepository.findById(imageId).orElseThrow().getStoredFilename();
		assertThat(Files.exists(imageStorageDirectory.resolve(storedFilename))).isTrue();

		String createResponseBody = mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"to delete","content":"has image","imageId":%d}
				""".formatted(imageId)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		Long postId = objectMapper.readTree(createResponseBody).get("postId").asLong();

		mockMvc.perform(delete("/api/community/posts/{postId}", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNoContent());

		assertThat(postRepository.findById(postId)).isEmpty();
		assertThat(imageRepository.findById(imageId)).isEmpty();
		assertThat(Files.exists(imageStorageDirectory.resolve(storedFilename))).isFalse();
	}

	@Test
	void postWithoutImageRemainsBackwardCompatibleWithNullImageFields() throws Exception {
		User author = createUser("no-image-author");
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();

		String createResponseBody = mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"no image title\",\"content\":\"no image content\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.imageId").doesNotExist())
			.andExpect(jsonPath("$.imageUrl").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		Long postId = objectMapper.readTree(createResponseBody).get("postId").asLong();

		mockMvc.perform(get("/api/community/posts/{postId}", postId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("no image title"))
			.andExpect(jsonPath("$.content").value("no image content"))
			.andExpect(jsonPath("$.imageId").doesNotExist())
			.andExpect(jsonPath("$.imageUrl").doesNotExist());
	}

	@Test
	void creatingPostWithAnotherUsersImageIdIsForbidden() throws Exception {
		User owner = createUser("owner-of-image");
		User stranger = createUser("stranger-user");
		String ownerToken = jwtTokenProvider.issue(owner.getId(), owner.getRole()).accessToken();
		String strangerToken = jwtTokenProvider.issue(stranger.getId(), stranger.getRole()).accessToken();
		MockMultipartFile image = new MockMultipartFile("image", "photo.png", "image/png", "bytes".getBytes());

		String uploadResponseBody = mockMvc.perform(multipart("/api/community/posts/images")
			.file(image)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		Long imageId = objectMapper.readTree(uploadResponseBody).get("imageId").asLong();
		long postCountBefore = postRepository.count();

		mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + strangerToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"stolen image","content":"content","imageId":%d}
				""".formatted(imageId)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		assertThat(postRepository.count()).isEqualTo(postCountBefore);
	}

	@Test
	void reusingAnAlreadyAssignedImageIdReturnsBadRequest() throws Exception {
		User author = createUser("reuse-author");
		String accessToken = jwtTokenProvider.issue(author.getId(), author.getRole()).accessToken();
		MockMultipartFile image = new MockMultipartFile("image", "photo.png", "image/png", "bytes".getBytes());

		String uploadResponseBody = mockMvc.perform(multipart("/api/community/posts/images")
			.file(image)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		Long imageId = objectMapper.readTree(uploadResponseBody).get("imageId").asLong();

		mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"first post","content":"content","imageId":%d}
				""".formatted(imageId)))
			.andExpect(status().isCreated());
		long postCountAfterFirst = postRepository.count();

		mockMvc.perform(post("/api/community/posts")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"title":"second post reusing image","content":"content","imageId":%d}
				""".formatted(imageId)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		assertThat(postRepository.count()).isEqualTo(postCountAfterFirst);
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
