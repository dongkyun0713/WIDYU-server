package com.widyu.fcm.dto;

import com.widyu.fcm.NotificationType;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 승인된 문구표 v0.4. 코드와 변형은 화면에 노출하지 않는다. */
public record NotificationCopy(String title, String body) {
    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");
    private static final Map<String, NotificationCopy> CATALOG = Map.ofEntries(
            copy("A01", "게시물 업로드가 완료됐어요!", "업로드한 게시물을 확인해보세요."),
            copy("A02-S", "{작성자 이름} 님이 게시물을 올렸어요!", "새로운 게시물을 확인하러 가보세요."),
            copy("A02-C", "{작성자 이름} 님이 새로운 소식을 전했어요!", "새로운 게시물을 확인하러 가보세요."),
            copy("A03", "{작성자 이름} 님이 게시물에 댓글을 달았어요!", "댓글을 확인해보세요."),
            copy("A04", "{작성자 이름} 님이 댓글에 답글을 달았어요!", "답글을 확인해보세요."),
            copy("A05", "{누른 사람 이름} 님이 게시물에 좋아요를 눌렀어요.", null),
            copy("A06-L", "{시니어 이름} 님이 게시물 잠금을 해제했어요.", "아직 잠금을 해제하지 않은 새 소식이 {남은 개수}개 남았어요."),
            copy("A06-Z", "{시니어 이름} 님이 게시물 잠금을 해제했어요.", "아직 잠금을 해제하지 않은 새 소식이 없어요."),
            copy("A06-FALLBACK", "게시물 잠금을 해제했어요.", "앨범에서 확인해보세요."),
            copy("A07", "{시니어 이름} 님이 새 게시물을 모두 확인했어요!", "새로운 소식을 올려주세요."),
            copy("M01", "약 복용하실 시간이에요!", null),
            copy("M02", "약 복용 인증이 아직 안 됐어요.", "약을 복용하셨다면 인증해 주세요."),
            copy("M03", "약 복용 인증 시간이 10분 남았어요.", "약을 복용하셨다면 지금 인증해 주세요."),
            copy("M04", "{시니어 이름} 님의 약 복용 인증이 아직 확인되지 않았어요.", "약을 드셨는지 직접 확인해보세요."),
            copy("M05", "{보호자 이름} 님이 약 알람을 변경했어요.", "바뀐 약 알람은 내일부터 적용돼요."),
            copy("M08", "{보호자 이름} 님이 새 약 알람을 등록했어요.", "내일부터 알람이 울려요."),
            copy("M09", "{보호자 이름} 님이 약 알람을 삭제했어요.", "내일부터 이 알람은 울리지 않아요."),
            copy("H01-S-OS", "건강 일정이 곧 있어요.", "앱에서 일정 시간과 내용을 확인해주세요."),
            copy("H01-S-INAPP", "{오전/오후 시각}에 {일정명} 일정이 있어요.", "잊지 않도록 일정을 확인해보세요."),
            copy("H01-C-SELF-OS", "건강 일정이 곧 있어요.", "앱에서 일정 시간과 내용을 확인해주세요."),
            copy("H01-C-SELF-INAPP", "{오전/오후 시각}에 {일정명} 일정이 있어요.", "잊지 않도록 일정을 확인해보세요."),
            copy("H01-C-SENIOR-OS", "{시니어 이름} 님의 건강 일정이 곧 있어요.", "앱에서 일정 시간과 내용을 확인해주세요."),
            copy("H01-C-SENIOR-INAPP", "{오전/오후 시각}에 {시니어 이름} 님의 일정이 있어요.", "{일정명} 일정을 확인해보세요."),
            copy("H02", "{보호자 이름} 님이 새 건강 일정을 등록했어요.", "앱에서 일정을 확인해보세요."),
            copy("H03", "{보호자 이름} 님이 건강 일정을 변경했어요.", "앱에서 바뀐 일정을 확인해보세요."),
            copy("H04", "{보호자 이름} 님이 건강 일정을 삭제했어요.", "앱에서 남은 일정을 확인해보세요."),
            copy("W01", "오늘 {실제 걸음 수}걸음 걸으셨어요.", "가능하다면 조금 더 걸어보는 건 어떨까요?"),
            copy("W02", "{보호자 이름} 님이 걷기 목표를 변경했어요.", "새 목표는 하루 {목표 걸음 수}걸음이에요."),
            copy("G01-S", "{목표명} 목표를 달성했어요!", "{포인트}P가 자동으로 적립됐어요."),
            copy("G01-C", "{시니어 이름} 님이 {목표명} 목표를 달성했어요!", "{포인트}P가 자동으로 적립됐어요."),
            copy("G02-S", "오늘 목표 {달성 개수}개를 달성했어요!", "총 {포인트 합계}P가 자동으로 적립됐어요."),
            copy("G02-C", "{시니어 이름} 님이 목표 {달성 개수}개를 달성했어요!", "총 {포인트 합계}P가 자동으로 적립됐어요."),
            copy("P01", "{포인트}P를 받았어요.", "{적립 사유}"),
            copy("P02", "{포인트}P를 사용했어요.", "{사용 사유}"),
            copy("X01-OS", "{보낸 사람 이름} 님이 메시지를 보냈어요!", "앱에서 메시지를 확인해주세요."),
            copy("X01-INAPP", "{보낸 사람 이름} 님이 메시지를 보냈어요!", "{메시지 미리보기}"),
            copy("X02-OS", "{보낸 사람 이름} 님이 응원메시지를 보냈어요!", "앱에서 응원메시지를 확인해주세요."),
            copy("X02-INAPP", "{보낸 사람 이름} 님이 응원메시지를 보냈어요!", "{메시지 미리보기}"),
            copy("S01", "평소와 다른 심박이 감지됐어요.", "괜찮으시면 취소를 눌러주세요. 시간 안에 누르지 않으면 보호자에게 알려드려요."),
            copy("S03", "보호자에게 알렸어요.", "연락을 기다리는 동안 안전한 곳에서 잠시 기다려주세요."),
            copy("S04", "{시니어 이름} 님의 심박 상태를 확인해주세요.", "평소와 다른 심박이 감지됐어요. 현재 상태와 위치를 확인해주세요."),
            copy("S06", "아직 보호자 대응이 확인되지 않았어요.", "{시니어 이름} 님의 심박 상태와 위치를 바로 확인해주세요."),
            copy("S08", "{시니어 이름} 님의 신체 지표가 평소와 달랐어요.", "본인은 괜찮다고 하셨어요. 필요하면 연락해보세요."),
            copy("Z01", "{시니어 이름} 님이 안심구역을 벗어났어요.", null),
            copy("Z02", "{시니어 이름} 님이 안심구역으로 돌아왔어요.", null),
            copy("S10", "괜찮다고 보호자에게 전해드렸어요.", null),
            copy("R01", "이제 가족 방장이 되었어요.", "가족 관리와 중요한 알림을 확인해주세요."));

    public static NotificationCopy of(NotificationType type, String code, Map<String, String> values) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("알림 문구 코드가 없습니다.");
        }
        if (type == null || type.copyCode() == null || !allowed(type, code)) {
            throw new IllegalArgumentException("알림 타입과 문구 코드가 일치하지 않습니다.");
        }
        NotificationCopy template = CATALOG.get(code);
        if (template == null) {
            throw new IllegalArgumentException("등록되지 않은 알림 문구 코드입니다.");
        }
        Map<String, String> safeValues = Map.of();
        if (values != null) {
            safeValues = values;
        }
        return new NotificationCopy(render(template.title(), safeValues), render(template.body(), safeValues));
    }

    private static boolean allowed(NotificationType type, String code) {
        if (type == NotificationType.SAFETY_SELF_CHECK) {
            return code.equals("S01");
        }
        if (type == NotificationType.HEART_RATE_EMERGENCY) {
            return code.equals("S01") || code.equals("S03") || code.equals("S04") || code.equals("S06");
        }
        if (type == NotificationType.SAFE_ZONE_EXITED) {
            return code.equals("Z01");
        }
        if (type == NotificationType.SAFE_ZONE_ENTERED) {
            return code.equals("Z02");
        }
        if (type == NotificationType.SAFETY_SENIOR_OK_NOTICE_HEART) {
            return code.equals("S08");
        }
        return code.startsWith(type.copyCode().substring(0, 3));
    }

    private static String render(String template, Map<String, String> values) {
        if (template == null) {
            return null;
        }
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = values.get(key);
            if ((value == null || value.isBlank()) && key.endsWith("이름")
                    && !key.equals("시니어 이름")) {
                value = values.get("actorDisplayName");
            }
            if ((value == null || value.isBlank()) && key.endsWith("이름")) {
                value = "가족";
            }
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("알림 문구 필수 값이 없습니다: " + key);
            }
            if (key.equals("메시지 미리보기") && value.length() > 40) {
                value = value.substring(0, 40);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Entry<String, NotificationCopy> copy(String code, String title, String body) {
        return Map.entry(code, new NotificationCopy(title, body));
    }
}
