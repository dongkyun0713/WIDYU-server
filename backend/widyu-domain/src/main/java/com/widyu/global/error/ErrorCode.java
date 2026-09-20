package com.widyu.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 인증/인가
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH_4010", "인증이 필요합니다."),
    EXPIRED_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_4017", "액세스 토큰이 만료되었습니다."),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_4018", "유효하지 않은 액세스 토큰입니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_4013", "리프레시 토큰이 유효하지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "AUTH_4030", "접근 권한이 없습니다."),
    TEMPORARY_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_4014", "임시 토큰이 만료되었습니다."),
    ALREADY_REGISTERED_EMAIL(HttpStatus.BAD_REQUEST, "AUTH_4001", "이미 등록된 이메일입니다."),
    INVALID_TEMPORARY_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_4015", "유효하지 않은 임시 토큰입니다."),
    MISSING_SOCIAL_TEMPORARY_TOKEN(HttpStatus.BAD_REQUEST, "AUTH_4016", "소셜 임시 토큰 헤더가 누락되었습니다."),
    INVALID_EMAIL(HttpStatus.UNAUTHORIZED, "AUTH_4011", "이메일이 올바르지 않습니다."),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "AUTH_4012", "비밀번호가 올바르지 않습니다."),
    UNSUPPORTED_OAUTH_PROVIDER(HttpStatus.BAD_REQUEST, "AUTH_4002", "지원하지 않는 소셜 로그인 제공자입니다."),
    INVALID_OAUTH_STATE(HttpStatus.BAD_REQUEST, "AUTH_4003", "유효하지 않은 OAuth state입니다."),
    SAME_PASSWORD(HttpStatus.BAD_REQUEST, "AUTH_4004", "기존 비밀번호와 동일한 비밀번호입니다."),
    OAUTH_ACCESS_TOKEN_IS_BLANK(HttpStatus.BAD_REQUEST, "AUTH_4005", "OAuth 액세스 토큰이 비어 있습니다."),
    SOCIAL_EMAIL_NOT_PROVIDED(HttpStatus.BAD_REQUEST, "AUTH_4006", "소셜 로그인 제공자가 이메일을 제공하지 않습니다."),
    SOCIAL_NAME_NOT_PROVIDED(HttpStatus.BAD_REQUEST, "AUTH_4007", "소셜 로그인 제공자가 이름을 제공하지 않습니다."),
    SOCIAL_ACCOUNT_ALREADY_LINKED(HttpStatus.BAD_REQUEST, "AUTH_4008", "이미 연동된 소셜 계정입니다."),
    SOCIAL_ACCOUNT_ALREADY_LINKED_TO_CURRENT_USER(HttpStatus.BAD_REQUEST, "AUTH_4009", "현재 사용자에게 이미 연동된 소셜 계정입니다."),
    SOCIAL_PROVIDER_ALREADY_LINKED(HttpStatus.BAD_REQUEST, "AUTH_4019", "이미 연동된 소셜 로그인 제공자입니다."),

    // 가족 관련
    FAMILY_LEADER_MUST_DELEGATE_BEFORE_WITHDRAW(HttpStatus.BAD_REQUEST, "FAMILY_4001", "방장 권한을 다른 구성원에게 위임한 후 탈퇴해주세요."),
    FAMILY_MEMBERSHIP_NOT_FOUND(HttpStatus.NOT_FOUND, "FAMILY_4040", "가족 구성원 정보를 찾을 수 없습니다."),

    // 부모 인증 관련
    INVITE_CODE_DUPLICATED(HttpStatus.BAD_REQUEST, "PARENT_4001", "이미 존재하는 초대코드입니다."),
    INVITE_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "PARENT_4040", "초대코드를 찾을 수 없습니다."),
    INVITE_CODE_DUPLICATED_IN_REQUEST(HttpStatus.BAD_REQUEST, "PARENT_4002", "요청 내 중복된 초대코드가 있습니다."),
    ALREADY_CONNECTED_TO_FAMILY(HttpStatus.BAD_REQUEST, "PARENT_4003", "이미 해당 가족에 연결되어 있습니다."),
    SENIOR_SIGNUP_REQUEST_EMPTY(HttpStatus.BAD_REQUEST, "PARENT_4004", "시니어 등록 요청 목록이 비어 있습니다."),
    FAMILY_CODE_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "PARENT_5001", "가족 코드 생성에 실패했습니다."),

    // 문자 인증
    SMS_VERIFICATION_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "SMS_4040", "문자 인증 코드가 존재하지 않습니다."),
    SMS_VERIFICATION_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "SMS_4000", "문자 인증 코드가 일치하지 않습니다."),
    SMS_SEND_FAILED(HttpStatus.BAD_GATEWAY, "SMS_5000", "SMS 전송에 실패했습니다."),
    AUTH_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "AUTH_4290", "요청 한도를 초과했습니다. Retry-After 이후 다시 시도해주세요."),
    AUTH_LIMIT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_5030", "인증 요청을 처리할 수 없습니다. 잠시 후 다시 시도해주세요."),
    INVALID_PHONE_NUMBER(HttpStatus.BAD_REQUEST, "SMS_4001", "유효하지 않은 전화번호 형식입니다."),
    PHONE_NUMBER_REQUIRED(HttpStatus.BAD_REQUEST, "SMS_4002", "전화번호는 필수입니다."),

    // 네이버
    NAVER_COMMUNICATION_ERROR(HttpStatus.BAD_GATEWAY, "NAVER_5000", "네이버 통신에 실패하였습니다."),
    NAVER_TOKEN_IS_BLANK(HttpStatus.BAD_REQUEST, "NAVER_4000", "네이버 토큰이 비어 있습니다."),
    NAVER_WITHDRAW_ERROR(HttpStatus.BAD_GATEWAY, "NAVER_5001", "네이버 계정 탈퇴에 실패하였습니다."),

    // 카카오
    KAKAO_COMMUNICATION_ERROR(HttpStatus.BAD_GATEWAY, "KAKAO_5000", "카카오 통신에 실패하였습니다."),
    KAKAO_TOKEN_IS_BLANK(HttpStatus.BAD_REQUEST, "KAKAO_4000", "카카오 토큰이 비어 있습니다."),
    KAKAO_WITHDRAW_ERROR(HttpStatus.BAD_GATEWAY, "KAKAO_5001", "카카오 계정 탈퇴에 실패하였습니다."),

    // 애플
    APPLE_COMMUNICATION_ERROR(HttpStatus.BAD_GATEWAY, "APPLE_5000", "애플 통신에 실패하였습니다."),
    APPLE_AUTHORIZATION_CODE_IS_BLANK(HttpStatus.BAD_REQUEST, "APPLE_4000", "애플 인증 코드가 비어 있습니다."),
    APPLE_TOKEN_EXCHANGE_FAILED(HttpStatus.BAD_GATEWAY, "APPLE_5001", "애플 토큰 교환에 실패하였습니다."),
    APPLE_TOKEN_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, "APPLE_5002", "애플 토큰 응답이 유효하지 않습니다."),
    APPLE_ID_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "APPLE_5003", "애플 ID 토큰이 유효하지 않습니다."),
    APPLE_SIGNATURE_VERIFICATION_FAILED(HttpStatus.UNAUTHORIZED, "APPLE_5004", "애플 ID 토큰 서명 검증에 실패하였습니다."),
    APPLE_PRIVATE_KEY_PARSING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "APPLE_5005", "애플 비밀 키 파싱에 실패하였습니다."),
    APPLE_WITHDRAW_ERROR(HttpStatus.BAD_GATEWAY, "APPLE_5006", "애플 계정 탈퇴에 실패하였습니다."),

    // 회원 관련
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER_4041", "회원을 찾을 수 없습니다."),
    SENIOR_PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER_4042", "시니어 프로필을 찾을 수 없습니다."),
    TEMPORARY_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "AUTH_4041", "임시 회원을 찾을 수 없습니다."),

    // fcm 관련
    FCM_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "FCM_4040", "FCM 토큰이 존재하지 않습니다."),
    FCM_NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "FCM_4041", "FCM 알림이 존재하지 않습니다."),
    INVALID_FCM_CATEGORY(HttpStatus.BAD_REQUEST, "FCM_4001", "유효하지 않은 알림 카테고리입니다."),

    // 알림 관련
    NOTIFICATION_COMMENTER_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION_4040", "댓글 작성자를 찾을 수 없습니다."),
    NOTIFICATION_ALBUM_AUTHOR_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION_4041", "게시물 작성자를 찾을 수 없습니다."),
    NOTIFICATION_GUARDIAN_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION_4042", "보호자를 찾을 수 없습니다."),
    NOTIFICATION_PARENT_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION_4043", "부모님 회원을 찾을 수 없습니다."),
    NOTIFICATION_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION_4044", "회원을 찾을 수 없습니다."),

    // 앨범 관련
    ALBUM_NOT_FOUND(HttpStatus.NOT_FOUND, "ALBUM_4040", "앨범을 찾을 수 없습니다."),
    ALBUM_ALREADY_LIKED(HttpStatus.BAD_REQUEST, "ALBUM_4001", "이미 좋아요한 앨범입니다."),
    ALBUM_NOT_LIKED(HttpStatus.BAD_REQUEST, "ALBUM_4002", "좋아요하지 않은 앨범입니다."),
    
    // 앨범 댓글 관련
    ALBUM_COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "ALBUM_COMMENT_4040", "댓글을 찾을 수 없습니다."),
    ALBUM_COMMENT_NOT_OWNER(HttpStatus.FORBIDDEN, "ALBUM_COMMENT_4030", "본인의 댓글만 수정/삭제할 수 있습니다."),
    ALBUM_COMMENT_PARENT_ALBUM_MISMATCH(HttpStatus.BAD_REQUEST, "ALBUM_COMMENT_4001", "다른 앨범의 댓글에는 대댓글을 작성할 수 없습니다."),
    ALBUM_COMMENT_DEPTH_EXCEEDED(HttpStatus.BAD_REQUEST, "ALBUM_COMMENT_4002", "대댓글은 2단계까지만 허용됩니다."),
    ALBUM_COMMENT_PARENT_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "ALBUM_COMMENT_4003", "삭제되거나 비활성화된 댓글에는 대댓글을 작성할 수 없습니다."),
    
    // 앨범 해금 관련
    ALBUM_UNLOCK_REQUIRED(HttpStatus.FORBIDDEN, "ALBUM_4031", "앨범을 보려면 해금이 필요합니다."),
    ALBUM_ALREADY_UNLOCKED(HttpStatus.BAD_REQUEST, "ALBUM_UNLOCK_4001", "이미 해금된 앨범입니다."),
    ALBUM_UNLOCK_SELF_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "ALBUM_UNLOCK_4002", "본인의 앨범은 해금할 수 없습니다."),
    ALBUM_UNLOCK_SENIOR_ONLY(HttpStatus.FORBIDDEN, "ALBUM_UNLOCK_4030", "시니어 회원만 앨범을 해금할 수 있습니다."),
    ALBUM_UNLOCK_INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "ALBUM_UNLOCK_4003", "포인트가 부족하여 해금할 수 없습니다."),
    ALBUM_UNLOCK_NOT_REQUIRED(HttpStatus.BAD_REQUEST, "ALBUM_UNLOCK_4004", "해금이 필요하지 않은 앨범입니다."),

    // 앨범 직접 업로드 관련
    ALBUM_UPLOAD_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "ALBUM_UPLOAD_4040", "업로드 세션을 찾을 수 없거나 만료되었습니다."),
    ALBUM_UPLOAD_SESSION_FORBIDDEN(HttpStatus.FORBIDDEN, "ALBUM_UPLOAD_4030", "본인의 업로드 세션만 사용할 수 있습니다."),
    ALBUM_UPLOAD_INCOMPLETE(HttpStatus.BAD_REQUEST, "ALBUM_UPLOAD_4001", "완료되지 않은 업로드 파트가 있습니다."),
    ALBUM_UPLOAD_FILE_MISMATCH(HttpStatus.BAD_REQUEST, "ALBUM_UPLOAD_4002", "업로드된 파일이 요청 정보와 일치하지 않습니다."),
    ALBUM_UPLOAD_ALREADY_IN_PROGRESS(HttpStatus.CONFLICT, "ALBUM_UPLOAD_4090", "업로드 완료 처리가 이미 진행 중입니다."),

    // 결제 관련
    PAYMENT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "PAY_5000", "결제 처리에 실패했습니다."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAY_4040", "결제 정보를 찾을 수 없습니다."),
    PAYMENT_PROCESSING(HttpStatus.CONFLICT, "PAY_4090", "결제가 처리 중입니다. 잠시 후 다시 시도해주세요."),

    // 포인트 관련
    POINT_CONCURRENT_UPDATE(HttpStatus.CONFLICT, "POINT_4090", "포인트 처리가 동시에 요청되었습니다. 잠시 후 다시 시도해주세요."),

    // 원시 센서 배치 관련
    SENSOR_BATCH_TOO_LARGE(HttpStatus.BAD_REQUEST, "SENSOR_4000", "센서 배치 크기가 허용 범위를 초과했습니다."),
    SENSOR_SAMPLE_INVALID(HttpStatus.BAD_REQUEST, "SENSOR_4001", "센서 축 구조가 올바르지 않습니다."),
    SENSOR_PAYLOAD_INVALID(HttpStatus.BAD_REQUEST, "SENSOR_4002", "센서 배치 본문을 읽을 수 없습니다."),
    SENSOR_BATCH_INVALID(HttpStatus.BAD_REQUEST, "SENSOR_4003", "센서 배치 필드가 올바르지 않습니다."),
    SENSOR_CLOCK_MAPPING_CONFLICT(HttpStatus.CONFLICT, "SENSOR_4090", "시계 환산 기준점 묶음이 등록된 값과 다릅니다."),

    // 위치 원본 관련
    LOCATION_FIX_INVALID(HttpStatus.BAD_REQUEST, "LOCATION_4000", "위치 원본 형식이 올바르지 않습니다."),

    // 측정회차 관련
    RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "RUN_4041", "측정회차를 찾을 수 없습니다."),
    RUN_ALREADY_OPEN(HttpStatus.CONFLICT, "RUN_4090", "이미 열린 측정회차가 있습니다."),
    RUN_DEVICE_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "RUN_4091", "이미 다른 열린 회차에 배정된 기기입니다."),
    RUN_MARKER_CONFLICT(HttpStatus.CONFLICT, "RUN_4092", "같은 마커 ID로 다른 내용이 등록되어 있습니다."),
    RUN_NOT_OPEN(HttpStatus.BAD_REQUEST, "RUN_4000", "열린 측정회차가 아닙니다."),
    RUN_RETENTION_INVALID(HttpStatus.BAD_REQUEST, "RUN_4001", "보존 정보가 올바르지 않습니다."),
    RUN_MARKER_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "RUN_4002", "마커 시각이 회차 구간을 벗어났습니다."),

    // 파일 업로드 관련
    FILE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_5000", "파일 업로드에 실패했습니다."),
    FILE_IS_EMPTY(HttpStatus.BAD_REQUEST, "FILE_4000", "파일이 비어있습니다."),
    FILE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "FILE_4001", "파일 크기가 허용 범위를 초과했습니다."),
    INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST, "FILE_4002", "지원하지 않는 파일 형식입니다."),
    INVALID_FILE_URL(HttpStatus.BAD_REQUEST, "FILE_4003", "유효하지 않은 파일 URL입니다."),
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "FILE_4004", "파일 크기가 너무 큽니다."),

    // 잘못된 요청
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "REQ_4000", "잘못된 요청입니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "REQ_4050", "지원하지 않는 HTTP 메서드입니다."),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "REQ_4130", "요청 크기가 허용 범위를 초과했습니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "REQ_4150", "지원하지 않는 미디어 타입입니다."),

    // 서버 오류
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SRV_5000", "서버 내부 오류가 발생했습니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SRV_5030", "현재 서비스를 사용할 수 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "SRV_4040", "찾을 수 없습니다");
    ;

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
