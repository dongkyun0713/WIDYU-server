package com.widyu.notification.application;

import com.widyu.fcm.FcmNotification;
import com.widyu.fcm.repository.FcmNotificationRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.notification.NotificationCenterFilter;
import com.widyu.notification.dto.response.NotificationCenterResponse;
import com.widyu.notification.dto.response.NotificationEnvelope;
import com.widyu.notification.dto.response.NotificationReadResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationCenterService {
    private static final int PAGE_SIZE = 10;

    private final FcmNotificationRepository notifications;
    private final MemberUtil memberUtil;

    @Transactional(readOnly = true)
    public NotificationCenterResponse list(String rawFilter, String rawCursor) {
        Member member = memberUtil.getCurrentMember();
        NotificationCenterFilter filter = NotificationCenterFilter.parse(rawFilter, member.getType());
        Long cursor = parseCursor(rawCursor);
        Instant instant = Instant.now();
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());

        List<FcmNotification> fetched = fetchPage(member.getId(), filter, now, cursor);
        boolean hasNext = fetched.size() > PAGE_SIZE;
        List<FcmNotification> page = fetched;
        if (hasNext) {
            page = fetched.subList(0, PAGE_SIZE);
        }
        String nextCursor = null;
        if (hasNext) {
            nextCursor = String.valueOf(page.get(page.size() - 1).getId());
        }

        List<NotificationEnvelope> items = page.stream().map(NotificationEnvelope::from).toList();
        Map<String, Integer> unreadCounts = countUnread(member, now);
        return NotificationCenterResponse.of(items, nextCursor, unreadCounts,
                String.valueOf(instant.toEpochMilli()), now);
    }

    @Transactional
    public NotificationReadResponse markAsRead(Long notificationId) {
        Member member = memberUtil.getCurrentMember();
        notifications.findByIdAndRecipientMemberId(notificationId, member.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.FCM_NOTIFICATION_NOT_FOUND));
        notifications.markAsReadIfUnread(notificationId, member.getId(), LocalDateTime.now());
        FcmNotification notification = notifications.findByIdAndRecipientMemberId(notificationId, member.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.FCM_NOTIFICATION_NOT_FOUND));
        return NotificationReadResponse.from(notification);
    }

    private List<FcmNotification> fetchPage(Long memberId, NotificationCenterFilter filter,
                                            LocalDateTime now, Long cursor) {
        PageRequest limit = PageRequest.of(0, PAGE_SIZE + 1);
        if (filter.isCategory()) {
            return notifications.findCenterPageByCategory(memberId, now, cursor,
                    filter.types(), filter.legacyCategories(), limit);
        }
        return notifications.findCenterPage(memberId, now, cursor,
                filter == NotificationCenterFilter.UNREAD, limit);
    }

    private Map<String, Integer> countUnread(Member member, LocalDateTime now) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (NotificationCenterFilter filter : NotificationCenterFilter.countedFilters(member.getType())) {
            long count;
            if (filter == NotificationCenterFilter.UNREAD) {
                count = notifications.countCenterUnread(member.getId(), now);
            } else {
                count = notifications.countCenterUnreadByCategory(member.getId(), now,
                        filter.types(), filter.legacyCategories());
            }
            counts.put(filter.name(), Math.toIntExact(count));
        }
        return counts;
    }

    private Long parseCursor(String rawCursor) {
        if (rawCursor == null) {
            return null;
        }
        try {
            long cursor = Long.parseLong(rawCursor);
            if (cursor <= 0) {
                throw new BusinessException(ErrorCode.INVALID_NOTIFICATION_CURSOR);
            }
            return cursor;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_NOTIFICATION_CURSOR);
        }
    }
}
