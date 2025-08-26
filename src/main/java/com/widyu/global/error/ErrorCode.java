package com.widyu.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 인증/인가
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH_4010", "인증이 필요합니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_4013", "리프레시 토큰이 유효하지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "AUTH_4030", "접근 권한이 없습니다."),
    TEMPORARY_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_4014", "임시 토큰이 만료되었습니다."),
    ALREADY_REGISTERED_EMAIL(HttpStatus.BAD_REQUEST, "AUTH_4001", "이미 등록된 이메일입니다."),
    INVALID_TEMPORARY_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_4015", "유효하지 않은 임시 토큰입니다."),
    INVALID_EMAIL(HttpStatus.UNAUTHORIZED, "AUTH_4011", "이메일이 올바르지 않습니다."),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "AUTH_4012", "비밀번호가 올바르지 않습니다."),

    // 문자 인증
    SMS_VERIFICATION_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "SMS_4040", "문자 인증 코드가 존재하지 않습니다."),
    SMS_VERIFICATION_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "SMS_4000", "문자 인증 코드가 일치하지 않습니다."),
    SMS_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "SMS_5000", "SMS 전송에 실패했습니다."),
    INVALID_PHONE_NUMBER(HttpStatus.BAD_REQUEST, "SMS_4001", "유효하지 않은 전화번호 형식입니다."),
    PHONE_NUMBER_REQUIRED(HttpStatus.BAD_REQUEST, "SMS_4002", "전화번호는 필수입니다."),

    // 회원 관련
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER_4041", "회원을 찾을 수 없습니다."),

    // fcm 관련
    FCM_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "FCM_4040", "FCM 토큰이 존재하지 않습니다."),
    FCM_NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "FCM_4041", "FCM 알림이 존재하지 않습니다."),

    // 결제 관련
    PAYMENT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "PAY_5000", "결제 처리에 실패했습니다."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAY_4040", "결제 정보를 찾을 수 없습니다."),

    // 잘못된 요청
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "REQ_4000", "잘못된 요청입니다."),

    // 서버 오류
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SRV_5000", "서버 내부 오류가 발생했습니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SRV_5030", "현재 서비스를 사용할 수 없습니다."),
    ;

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
