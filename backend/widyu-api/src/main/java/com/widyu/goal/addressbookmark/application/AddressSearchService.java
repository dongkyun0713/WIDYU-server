package com.widyu.goal.addressbookmark.application;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.goal.addressbookmark.client.JusoApiClient;
import com.widyu.goal.addressbookmark.dto.external.JusoApiResponse;
import com.widyu.goal.addressbookmark.dto.response.AddressSearchResponse;
import com.widyu.goal.addressbookmark.dto.response.AddressSearchResponse.AddressItem;
import com.widyu.goal.addressbookmark.dto.response.GeocodingResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AddressSearchService {

    private final JusoApiClient jusoApiClient;
    private final GeocodingService geocodingService;

    @Value("${juso.api.confm-key}")
    private String confmKey;

    public AddressSearchResponse search(String keyword, int page, int size) {
        JusoApiResponse response = jusoApiClient.searchAddress(confmKey, keyword, page, size, "json");

        JusoApiResponse.Common common = response.results().common();
        if (!"0".equals(common.errorCode())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "주소 검색 실패: " + common.errorMessage());
        }

        List<JusoApiResponse.JusoItem> jusoItems = response.results().juso();
        List<AddressItem> addresses = getAddresses(jusoItems);

        return AddressSearchResponse.of(
                addresses,
                AddressSearchResponse.parseIntSafe(common.totalCount()),
                AddressSearchResponse.parseIntSafe(common.currentPage()),
                AddressSearchResponse.parseIntSafe(common.countPerPage())
        );
    }

    private List<AddressItem> getAddresses(List<JusoApiResponse.JusoItem> jusoItems) {
        if (jusoItems == null) {
            return List.of();
        }
        return jusoItems.stream()
                .map(item -> {
                    Double latitude = null;
                    Double longitude = null;
                    try {
                        GeocodingResponse geo = geocodingService.geocode(item.roadAddrPart1());
                        latitude = geo.latitude();
                        longitude = geo.longitude();
                    } catch (Exception e) {
                        log.warn("좌표 변환 실패: {}", item.roadAddrPart1());
                    }
                    return AddressItem.from(item, latitude, longitude);
                })
                .toList();
    }
}
