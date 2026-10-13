package com.widyu.location.parentlocation.dto.request;

import com.widyu.parentlocation.LocationType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ParentLocationUpdateRequest(
    @NotNull(message = "시니어 회원 ID는 필수입니다.")
    Long memberId,
    @NotNull(message = "장소 타입은 필수입니다.")
    LocationType locationType,
    @NotBlank(message = "장소 주소는 필수입니다.")
    @Size(max = 255, message = "장소 주소는 255자 이하로 입력해 주세요.")
    String placeAddress,
    @NotNull(message = "위도는 필수입니다.")
    @DecimalMin(value = "-90", message = "위도는 -90 이상 90 이하여야 합니다.")
    @DecimalMax(value = "90", message = "위도는 -90 이상 90 이하여야 합니다.")
    Double latitude,
    @NotNull(message = "경도는 필수입니다.")
    @DecimalMin(value = "-180", message = "경도는 -180 이상 180 이하여야 합니다.")
    @DecimalMax(value = "180", message = "경도는 -180 이상 180 이하여야 합니다.")
    Double longitude,
    @Size(max = 255, message = "장소 이름은 255자 이하로 입력해 주세요.")
    String name
) {
}
