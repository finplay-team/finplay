package com.finplay.api.domain.auth.exception;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;

public class EmailChangeConflictException extends BusinessException {

	public EmailChangeConflictException() {
		super(ErrorCode.DUPLICATE_RESOURCE);
	}
}
