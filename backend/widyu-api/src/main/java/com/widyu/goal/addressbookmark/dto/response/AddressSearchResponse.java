package com.widyu.goal.addressbookmark.dto.response;

import com.widyu.goal.addressbookmark.dto.external.JusoApiResponse;
import java.util.List;

public record AddressSearchResponse(
        List<AddressItem> addresses,
        int totalCount,
        int currentPage,
        int countPerPage
) {
    public static AddressSearchResponse of(List<AddressItem> addresses, int totalCount,
                                           int currentPage, int countPerPage) {
        return new AddressSearchResponse(addresses, totalCount, currentPage, countPerPage);
    }

    public record AddressItem(
            String roadAddr,
            String jibunAddr,
            String zipNo,
            String bdNm,
            String siNm,
            String sggNm,
            String emdNm,
            Double latitude,
            Double longitude
    ) {
        public static AddressItem from(JusoApiResponse.JusoItem item, Double latitude, Double longitude) {
            return new AddressItem(
                    item.roadAddrPart1(),
                    item.jibunAddr(),
                    item.zipNo(),
                    item.bdNm(),
                    item.siNm(),
                    item.sggNm(),
                    item.emdNm(),
                    latitude,
                    longitude
            );
        }
    }

    public static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
