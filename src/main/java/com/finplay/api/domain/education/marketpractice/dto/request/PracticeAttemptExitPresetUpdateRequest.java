// 현재 실행 세대의 손절·익절 프리셋 선택 요청 (042 EXITPRESET-003)
package com.finplay.api.domain.education.marketpractice.dto.request;

import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import jakarta.validation.constraints.NotNull;

/**
 * 정의된 집합 밖의 문자열은 Jackson이 열거형으로 못 바꿔 {@code HttpMessageNotReadableException}이 되고,
 * {@code GlobalExceptionHandler}가 그것을 400 {@code VALIDATION_ERROR}로 매핑한다 — 별도 검증이 필요 없다.
 * {@code @NotNull}은 키를 아예 빼거나 명시적 null을 보낸 경우를 잡는다.
 */
public record PracticeAttemptExitPresetUpdateRequest(@NotNull
ExitPreset preset) {
}
