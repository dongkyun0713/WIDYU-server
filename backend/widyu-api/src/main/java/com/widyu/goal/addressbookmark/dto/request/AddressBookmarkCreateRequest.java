package com.widyu.goal.addressbookmark.dto.request;

import com.widyu.addressbookmark.AddressBookmark;
import com.widyu.global.entity.Status;
import com.widyu.member.Member;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddressBookmarkCreateRequest(
        @NotBlank(message = "도로명 주소는 필수입니다.")
        @Size(max = 255, message = "도로명 주소는 255자 이하로 입력해 주세요.")
        String roadAddress,
        @NotBlank(message = "지번 주소는 필수입니다.")
        @Size(max = 255, message = "지번 주소는 255자 이하로 입력해 주세요.")
        String address,
        @Size(max = 255, message = "장소 이름은 255자 이하로 입력해 주세요.")
        String name,
        @DecimalMin(value = "-90", message = "위도는 -90 이상 90 이하여야 합니다.")
        @DecimalMax(value = "90", message = "위도는 -90 이상 90 이하여야 합니다.")
        Double latitude,
        @DecimalMin(value = "-180", message = "경도는 -180 이상 180 이하여야 합니다.")
        @DecimalMax(value = "180", message = "경도는 -180 이상 180 이하여야 합니다.")
        Double longitude,
        @Size(max = 255, message = "도로명은 255자 이하로 입력해 주세요.")
        String road,
        @Size(max = 255, message = "지번은 255자 이하로 입력해 주세요.")
        String jibun
) {
    public AddressBookmark toEntity(Member member) {
        return AddressBookmark.builder()
                .member(member)
                .roadAddress(roadAddress)
                .address(address)
                .name(name)
                .latitude(latitude)
                .longitude(longitude)
                .road(road)
                .jibun(jibun)
                .status(Status.ACTIVE)
                .build();
    }
}
