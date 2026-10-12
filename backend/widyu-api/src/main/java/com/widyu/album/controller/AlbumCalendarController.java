package com.widyu.album.controller;

import com.widyu.album.controller.docs.AlbumCalendarDocs;
import com.widyu.album.application.AlbumCalendarService;
import com.widyu.album.dto.FamilyAlbumPageResponse;
import com.widyu.global.response.ApiResponseTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/albums/calendar")
public class AlbumCalendarController implements AlbumCalendarDocs {

    private final AlbumCalendarService albumCalendarService;

    @GetMapping
    public ApiResponseTemplate<List<Integer>> getDaysWithEvents(
            @RequestParam int year,
            @RequestParam int month
    ) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("OK")
                .body(albumCalendarService.getDaysWithEvents(year, month));
    }

    @GetMapping("/family")
    public ApiResponseTemplate<FamilyAlbumPageResponse> getFamilyAlbumsByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long cursor
    ) {
        return ApiResponseTemplate.ok()
                .code("200")
                .message("OK")
                .body(albumCalendarService.getFamilyAlbumsByDateWithCursor(date, cursor));
    }
}
