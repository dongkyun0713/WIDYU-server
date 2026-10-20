package com.widyu.heart.application;

import java.sql.SQLIntegrityConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/** 운영과 같은 모양의 무결성 예외. 제약 이름은 Hibernate가 DB 메시지에서 뽑은 값으로 원인 사슬에 들어 있다. */
final class IntegrityViolationFixture {

    /** MySQL이 보고하는 심박 UK 이름(테이블 접두 포함). */
    static final String HEART_EVENT_UK = "heart_rate_event.uk_heart_event_member_time";
    /** 같은 트랜잭션에서 저장하는 위급 행의 UK. 컬럼도 (member_id, measured_at)으로 같다. */
    static final String HEART_EMERGENCY_UK = "heart_rate_emergency.uk_heart_emergency_member_time";

    private IntegrityViolationFixture() {
    }

    static DataIntegrityViolationException violating(String constraintName) {
        SQLIntegrityConstraintViolationException sqlError =
                new SQLIntegrityConstraintViolationException("Duplicate entry for key '" + constraintName + "'");
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", sqlError, constraintName));
    }
}
