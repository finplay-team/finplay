// 현재 대본 구간에 공개된 원인이 있는지를 나타내는 두 값짜리 상태
package com.finplay.api.domain.education.marketpractice.entity;

/**
 * 값이 <b>둘뿐이다</b>(041 SCENARIO-016). {@code REVEALED}는 지금 구간의 사건이 공개됐다는 뜻이고
 * {@code NONE_KNOWN}은 "알려진 원인이 없다"는 뜻이다.
 *
 * <p><b>아직 공개 시점이 되지 않은 사건이 있는 구간도 {@code NONE_KNOWN}이다.</b> "아직 안 열렸다"를
 * 구분 가능하게 만들면 그 값 자체가 사건의 존재를 알려 SCENARIO-015(원인은 사후에만 열린다)를 어긴다.
 * 본편(012)이 {@code EMPTY}와 {@code NOT_YET}을 가른 것은 카드 생성 지연이 실재하기 때문이며, 대본에
 * 사건이 이미 다 있는 튜토리얼에서 같은 구분을 두면 미래 정보만 샌다.
 */
public enum PracticeCauseStatus {
	REVEALED,
	NONE_KNOWN
}
