package com.widyu.admin.application;

import com.widyu.admin.dto.response.AdminDashboardResponse;
import com.widyu.admin.dto.response.AdminDashboardResponse.DailyCount;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.global.entity.Status;
import com.widyu.heart.repository.HeartRateEmergencyRepository;
import com.widyu.member.MemberRole;
import com.widyu.member.MemberType;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.pay.PaymentStatus;
import com.widyu.pay.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final MemberRepository memberRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final AlbumRepository albumRepository;
    private final PaymentRepository paymentRepository;
    private final HeartRateEmergencyRepository heartRateEmergencyRepository;

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("MM/dd");

    @Transactional(readOnly = true)
    public AdminDashboardResponse getDashboard() {
        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        LocalDateTime yesterdayStart = todayStart.minusDays(1);
        LocalDateTime weekStart = todayStart.minusDays(todayStart.getDayOfWeek().getValue() - 1);
        LocalDateTime monthStart = todayStart.with(TemporalAdjusters.firstDayOfMonth());

        ZonedDateTime todayStartZoned = ZonedDateTime.now().toLocalDate().atStartOfDay(ZonedDateTime.now().getZone());
        ZonedDateTime monthStartZoned = todayStartZoned.with(TemporalAdjusters.firstDayOfMonth());

        List<DailyCount> weeklyMemberTrend = IntStream.range(0, 7)
                .mapToObj(i -> {
                    LocalDateTime start = todayStart.minusDays(6 - i);
                    LocalDateTime end = start.plusDays(1);
                    long count = memberRepository.countByCreatedAtBetweenAndRoleNot(start, end, MemberRole.ADMIN);
                    return new DailyCount(start.format(DAY_FMT), count);
                })
                .toList();

        List<DailyCount> weeklyAlbumTrend = IntStream.range(0, 7)
                .mapToObj(i -> {
                    LocalDateTime start = todayStart.minusDays(6 - i);
                    LocalDateTime end = start.plusDays(1);
                    long count = albumRepository.countActiveAlbumsCreatedBetween(start, end);
                    return new DailyCount(start.format(DAY_FMT), count);
                })
                .toList();

        return AdminDashboardResponse.of(
                memberRepository.countByRoleNot(MemberRole.ADMIN),
                memberRepository.countByTypeAndRoleNot(MemberType.SENIOR, MemberRole.ADMIN),
                memberRepository.countByTypeAndRoleNot(MemberType.GUARDIAN, MemberRole.ADMIN),
                memberRepository.countByCreatedAtAfterAndRoleNot(todayStart, MemberRole.ADMIN),
                memberRepository.countByCreatedAtBetweenAndRoleNot(yesterdayStart, todayStart, MemberRole.ADMIN),
                familyMembershipRepository.count(),
                albumRepository.countNewAlbums(todayStart),
                albumRepository.countNewAlbums(weekStart),
                albumRepository.countNewAlbums(monthStart),
                albumRepository.countByStatus(Status.PROCESSING),
                paymentRepository.sumAmountSince(todayStartZoned, PaymentStatus.DONE),
                paymentRepository.sumAmountSince(monthStartZoned, PaymentStatus.DONE),
                paymentRepository.countByStatus(PaymentStatus.READY),
                heartRateEmergencyRepository.count(),
                heartRateEmergencyRepository.countByMeasuredAtAfter(todayStart),
                weeklyMemberTrend,
                weeklyAlbumTrend
        );
    }
}
