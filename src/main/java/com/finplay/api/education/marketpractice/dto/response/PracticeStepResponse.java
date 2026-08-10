// 투자 실습 3단계 중 한 단계의 상태·잠금 여부·evidence를 담는 응답 DTO
package com.finplay.api.education.marketpractice.dto.response;

public record PracticeStepResponse(Integer step, String status, Boolean locked, PracticeEvidenceResponse evidence) {
}
