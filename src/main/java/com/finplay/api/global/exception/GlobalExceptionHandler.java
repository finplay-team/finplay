// 모든 예외를 공통 오류 포맷으로 변환하는 전역 예외 핸들러
package com.finplay.api.global.exception;

import com.finplay.api.global.filter.RequestIdFilter;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
		ErrorCode errorCode = ex.getErrorCode();
		return build(errorCode, ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationException(
		MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldErrors().stream()
			.findFirst()
			.map(error -> error.getDefaultMessage())
			.orElse(ErrorCode.VALIDATION_ERROR.getDefaultMessage());
		return build(ErrorCode.VALIDATION_ERROR, message);
	}

	@ExceptionHandler({
		HttpMessageNotReadableException.class,
		MissingServletRequestParameterException.class,
		ConstraintViolationException.class,
		MethodArgumentTypeMismatchException.class,
		MissingRequestHeaderException.class
	})
	public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
		// 잘못된 JSON 본문·필수 파라미터 누락·파라미터 검증 실패·쿼리 파라미터 타입 변환 실패·필수 헤더 누락을 모두 VALIDATION_ERROR(400)로 매핑한다.
		// 사용자 입력 원문을 반사하지 않도록 고정 기본 메시지만 응답한다.
		return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getDefaultMessage());
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(
		MaxUploadSizeExceededException ex) {
		// 커뮤니티 게시물 첨부 이미지(COM-006) 등 multipart 업로드가 spring.servlet.multipart.max-file-size를
		// 초과하면 Spring이 이 예외를 던진다. 400 VALIDATION_ERROR로 매핑한다.
		return build(ErrorCode.VALIDATION_ERROR, "첨부 파일 크기가 허용 범위를 초과했습니다.");
	}

	@ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
	public ResponseEntity<ErrorResponse> handleNotFound(Exception ex) {
		return build(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getDefaultMessage());
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
		HttpRequestMethodNotSupportedException ex) {
		return build(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getDefaultMessage());
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		// 예상 밖 오류는 내부 정보(스택·메시지)를 노출하지 않고 고정 코드·메시지만 응답한다.
		// 운영 추적을 위해 스택 전체는 서버 로그에만 남긴다.
		log.error(
			"unexpected error requestId={}", MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY), ex);
		// PRD §5 표에 500 코드가 없어 클라이언트 공통 코드로 INTERNAL_ERROR를 쓴다.
		ErrorResponse body = new ErrorResponse(new ErrorResponse.ErrorBody(
			"INTERNAL_ERROR",
			"서버 내부 오류가 발생했습니다.",
			MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY)));
		return ResponseEntity.status(500).body(body);
	}

	private ResponseEntity<ErrorResponse> build(ErrorCode errorCode, String message) {
		ErrorResponse body = ErrorResponse.of(
			errorCode, message, MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY));
		return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
	}
}
