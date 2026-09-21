package com.widyu.consent;

/**
 * 인앱에서 항목별로 받는 서비스 동의 항목(정책서 v1.1 B 1.5.10 표, LLD-0055 3절).
 * 서버가 값을 고정하고 앱은 이 이름만 보낸다. 서면 연구 참여 동의는 여기 들어가지 않는다.
 */
public enum ConsentKey {

    /** 개인정보 수집·이용 */
    PRIVACY_PERSONAL,

    /** 민감(건강)정보 수집·이용. 다른 동의와 별도로 받는다 */
    PRIVACY_HEALTH,

    /** 위치정보 수집·이용 */
    LOCATION,

    /** 보호자에게 위치 제공 */
    GUARDIAN_LOCATION_PROVIDE,

    /** 위치 제공사실 통보를 모아서(최대 30일) 받는 데 동의. 없거나 false면 즉시 통보 */
    LOCATION_NOTICE_BATCHED,

    /** 보유 기간 고지 확인 */
    RETENTION_NOTICE
}
