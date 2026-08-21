// 게시물 첨부 이미지 업로드·다운로드 API의 인증, 요청 계약, 오류 매핑을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.domain.community.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.community.dto.response.CommunityPostImageFileResponse;
import com.finplay.api.domain.community.dto.response.CommunityPostImageResponse;
import com.finplay.api.domain.community.service.CommunityPostImageService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CommunityPostImageController.class)
@Import(SecurityConfig.class)
class CommunityPostImageControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CommunityPostImageService service;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void uploadImageReturnsCreatedWithImageIdAndUrlWhenFormatIsAllowed() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		MockMultipartFile image = new MockMultipartFile("image", "photo.png", "image/png", "bytes".getBytes());
		when(service.uploadImage(USER_ID, image))
			.thenReturn(new CommunityPostImageResponse(7L, "/api/community/posts/images/7/file"));

		mockMvc.perform(multipart("/api/community/posts/images")
			.file(image)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.imageId").value(7))
			.andExpect(jsonPath("$.imageUrl").value("/api/community/posts/images/7/file"));

		verify(service).uploadImage(USER_ID, image);
	}

	@Test
	void uploadImageReturnsCommonValidationErrorWhenServiceRejectsFormat() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		MockMultipartFile file = new MockMultipartFile("image", "notes.txt", "text/plain", "bytes".getBytes());
		when(service.uploadImage(USER_ID, file))
			.thenThrow(new BusinessException(
				ErrorCode.VALIDATION_ERROR, "허용하지 않는 이미지 형식입니다. JPEG, PNG, WEBP만 첨부할 수 있습니다."));

		mockMvc.perform(multipart("/api/community/posts/images")
			.file(file)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("허용하지 않는 이미지 형식입니다. JPEG, PNG, WEBP만 첨부할 수 있습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(service).uploadImage(USER_ID, file);
	}

	@Test
	void uploadImageReturnsCommonValidationErrorWhenServiceRejectsEmptyFile() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		MockMultipartFile empty = new MockMultipartFile("image", "empty.png", "image/png", new byte[0]);
		when(service.uploadImage(USER_ID, empty))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "첨부할 이미지 파일이 없습니다."));

		mockMvc.perform(multipart("/api/community/posts/images")
			.file(empty)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("첨부할 이미지 파일이 없습니다."));

		verify(service).uploadImage(USER_ID, empty);
	}

	@Test
	void uploadImageRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		MockMultipartFile image = new MockMultipartFile("image", "photo.png", "image/png", "bytes".getBytes());

		mockMvc.perform(multipart("/api/community/posts/images").file(image))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(service);
	}

	@Test
	void getImageFileReturnsOkWithBytesAndContentTypeWhenImageExists() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		byte[] content = "image-bytes".getBytes();
		when(service.loadImageFile(USER_ID, 7L))
			.thenReturn(new CommunityPostImageFileResponse(new ByteArrayResource(content), "image/png"));

		mockMvc.perform(get("/api/community/posts/images/7/file")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"));

		verify(service).loadImageFile(USER_ID, 7L);
	}

	@Test
	void getImageFileReturnsCommonNotFoundErrorWhenImageDoesNotExist() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(service.loadImageFile(USER_ID, 404L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(get("/api/community/posts/images/404/file")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("대상을 찾을 수 없습니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(service).loadImageFile(USER_ID, 404L);
	}

	@Test
	void getImageFileRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/community/posts/images/7/file"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(service);
	}
}
