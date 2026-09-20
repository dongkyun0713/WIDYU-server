package com.widyu.location.access.dto.response;

import com.widyu.location.access.LocationAccessLog;
import com.widyu.location.access.LocationAccessPath;
import java.time.LocalDateTime;

/**
 * 시니어가 보는 「내 위치를 누가 언제 봤나」 한 줄(LLD-0056 3절).
 *
 * <p>좌표는 담지 않는다. 열람 사실만 알리는 것이 목적이다.
 * {@code viewerName}은 같은 가족의 보호자 이름이라 본인에게는 공개할 수 있다.
 */
public record LocationAccessLogResponse(
        Long id,
        Long viewerMemberId,
        String viewerName,
        LocationAccessPath path,
        LocalDateTime accessedAt,
        LocalDateTime notifiedAt
) {

    public static LocationAccessLogResponse of(LocationAccessLog accessLog, String viewerName) {
        return new LocationAccessLogResponse(
                accessLog.getId(),
                accessLog.getViewerMemberId(),
                viewerName,
                accessLog.getPath(),
                accessLog.getAccessedAt(),
                accessLog.getNotifiedAt()
        );
    }
}
