// 이메일 변경 확인 성공 직후 유니크 제약 경합이 발생했을 때 던지는 예외
package com.finplay.api.auth.exception;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;

public class EmailChangeConflictException extends BusinessException {

	public EmailChangeConflictException() {
		super(ErrorCode.DUPLICATE_RESOURCE);
	}
}
