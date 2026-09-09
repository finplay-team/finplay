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
		return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getDefaultMessage());
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(
		MaxUploadSizeExceededException ex) {
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
		log.error(
			"unexpected error requestId={}", MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY), ex);
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
