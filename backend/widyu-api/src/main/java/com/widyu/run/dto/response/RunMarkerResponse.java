package com.widyu.run.dto.response;

import com.widyu.run.RunMarker;

public record RunMarkerResponse(
        String markerId,
        String kind,
        String label,
        String sourceElapsedNs,
        Long tsMs,
        String source,
        String sourceDeviceId,
        String clockMappingId
) {

    public static RunMarkerResponse from(RunMarker marker) {
        return new RunMarkerResponse(
                marker.getMarkerId(),
                marker.getKind(),
                marker.getLabel(),
                // 나노초는 받은 대로 문자열로 돌려준다(64비트 정밀도).
                String.valueOf(marker.getSourceElapsedNs()),
                marker.getTsMs(),
                marker.getSource(),
                marker.getSourceDeviceId(),
                marker.getClockMappingId());
    }
}
