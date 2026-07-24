// 모든 예외를 공통 오류 포맷으로 변환하는 전역 예외 핸들러
package com.finplay.api.common;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

	@ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
	public ResponseEntity<ErrorResponse> handleNotFound(Exception ex) {
		return build(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getDefaultMessage());
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		// 예상 밖 오류는 내부 정보(스택·메시지)를 노출하지 않고 고정 코드·메시지만 응답한다.
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
