package com.widyu.location.access;

/**
 * 보호자가 시니어 위치를 읽은 경로(ADR-0036 결정 3, LLD-0056 3절).
 * 다섯 갈래가 전부이며, 새 읽기 경로를 만들면 값을 늘리고 그 경로에도 기록 훅을 건다.
 */
public enum LocationAccessPath {

    /** REST 최근 위치 조회 */
    REST_LAST,

    /** REST 15분 이동 경로 조회 */
    REST_TRAIL,

    /** REST 가족 시니어 목록 조회. 목록에 담긴 시니어마다 한 행이다 */
    REST_FAMILY,

    /** STOMP {@code /topic/location/senior/{id}} 구독. 구독 시점에 한 번만 남긴다 */
    WS_SUBSCRIBE,

    /** 보호자 홈 카드의 외출 상태. 좌표가 아니지만 위치에서 파생되므로 기록한다 */
    HOME_OUTING
}
