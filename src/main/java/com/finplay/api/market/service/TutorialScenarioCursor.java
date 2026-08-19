// 대본 위치를 가리키는 (구간 id, 구간 내 가상 분) 한 쌍
package com.finplay.api.market.service;

public record TutorialScenarioCursor(String stageId, int stageMinute) {
}
