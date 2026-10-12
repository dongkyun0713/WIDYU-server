"""relay 핵심 로직을 외부 호출 없이 확인한다(LLD-0084). 실행: cd monitoring/alert-relay && python3 -m unittest test_relay

Loki·GitHub·Discord·Claude 호출은 모두 대역으로 바꾼다. anthropic SDK가 없어도 돌도록 예외 클래스만 흉내 낸다.
"""
import json
import os
import sqlite3
import sys
import tempfile
import types
import unittest
from unittest import mock

if "anthropic" not in sys.modules:
    fake = types.ModuleType("anthropic")
    fake.APIError = type("APIError", (Exception,), {})
    fake.APIStatusError = type("APIStatusError", (fake.APIError,), {"status_code": 500})
    fake.RateLimitError = type("RateLimitError", (fake.APIStatusError,), {})
    fake.APIConnectionError = type("APIConnectionError", (fake.APIError,), {})
    sys.modules["anthropic"] = fake

import relay  # noqa: E402

SHA_A = "a" * 40
SHA_B = "b" * 40
FRAME_FILE = "backend/widyu-api/src/main/java/com/widyu/fcm/application/FcmOutboxTransactions.java"
API_LABELS = {"kind": "api", "api": "푸시 결과 반영", "method": "POST",
              "path": "/api/v1/fcm/{notificationId}/finish", "exceptionType": "IllegalStateException"}
SAMPLE_API_LINE = (
    "2026-10-12 10:00:00.000 ERROR traceId=3f2a9c1e [http-nio-8080-exec-1] c.w.g.e.GlobalExceptionHandler - "
    "[EXCEPTION] Time=2026-10-12T10:00:00+09:00 Api=앨범 업로드 완료 Method=POST "
    "Path=/api/v1/albums/uploads/{sessionId}/complete Actor=USER#12 Target=sessionId=abc "
    "ExceptionType=java.lang.IllegalStateException Message=member 01012345678 upload 'kim@x.com' failed memberId=12 "
    "RequestURI=/api/v1/albums/uploads/abc/complete StackTrace="
    "com.widyu.album.application.AlbumUploadSessionFacadeImpl.complete(AlbumUploadSessionFacadeImpl.java:142)\\n"
    "com.widyu.album.application.AlbumUploadSessionFacadeImpl$$SpringCGLIB$$0.complete(<generated>)\\n"
    "com.widyu.album.controller.AlbumUploadSessionController.completeUpload(AlbumUploadSessionController.java:58)\\n"
    "Caused by: java.sql.SQLException: Duplicate entry '01099998888' for key 'uk_phone'\\n"
    "org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885)"
)


def item(message="처리 실패 status=503", ts=1_760_230_800_000_000_000):
    return {"exceptionType": "IllegalStateException", "message": message, "ts": ts,
            "raw_stack": "com.widyu.fcm.application.FcmOutboxTransactions.finish(FcmOutboxTransactions.java:87)"}


class StoreCase(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.directory.name, "relay.sqlite3")
        self.store = relay.Store(self.path)
        self.claude = mock.Mock(return_value="원인 추정: 테스트")
        patches = [
            mock.patch.object(relay, "ask_claude", self.claude),
            mock.patch.object(relay, "deployed_sha", return_value=SHA_A),
            mock.patch.object(relay, "fetch_source", return_value=(FRAME_FILE, ["line"] * 100)),
            mock.patch.object(relay, "changed_files", return_value=[]),
        ]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)

    def tearDown(self):
        self.store.close()
        self.directory.cleanup()


class MessageTest(unittest.TestCase):
    def test_api_line_is_parsed_and_masked(self):
        parsed = relay.parse_line("api", SAMPLE_API_LINE)
        self.assertEqual((parsed["traceId"], parsed["actor"], parsed["target"]), ("3f2a9c1e", "USER#12", "sessionId=abc"))
        self.assertEqual(parsed["message"], "member <num> upload '<값>' failed memberId=<id>")
        self.assertNotIn("01099998888", parsed["raw_stack"])
        self.assertIn("for key 'uk_phone'", parsed["raw_stack"])

    def test_mask_keeps_error_identifiers_and_hides_personal_values(self):
        masked = relay.mask("HTTP 503 SQL 1062 SQLState 23000 Duplicate entry 'kim' for key 'uk_member_phone' "
                            "on table 'member' phone=010-1234-5678 session=ab12 a1b2c3d4-0000-1111-2222-333344445555")
        self.assertIn("HTTP 503 SQL 1062 SQLState 23000", masked)
        self.assertIn("for key 'uk_member_phone'", masked)
        self.assertIn("table 'member'", masked)
        self.assertIn("entry '<값>'", masked)
        for secret in ("kim'", "010-1234-5678", "ab12", "a1b2c3d4"):
            self.assertNotIn(secret, masked)

    def test_api_embed_shows_feature_endpoint_actor_and_count(self):
        parsed = relay.parse_line("api", SAMPLE_API_LINE)
        parsed["ts"] = 1_760_230_800_000_000_000
        labels = dict(API_LABELS, api="앨범 업로드 완료", path="/api/v1/albums/uploads/{sessionId}/complete")
        embed = relay.build_embed("api", labels, {"count": 5, "items": [parsed] * 3}, "https://g/d/x")
        self.assertEqual(embed["title"], "🚨 [DEV] 앨범 업로드 완료 실패")
        self.assertTrue(embed["description"].startswith("**POST /api/v1/albums/uploads/{sessionId}/complete**"))
        self.assertIn("👤 회원 #12 → sessionId=abc", embed["description"])
        self.assertIn("최근 15분 5건 · 앞 3건 표시", embed["description"])
        self.assertEqual(relay.discord_payload(embed)["allowed_mentions"], {"parse": []})

    def test_websocket_line_becomes_destination_centered_alert(self):
        line = ("2026-10-12 10:00:00.000 ERROR traceId=none [clientInboundChannel-3] c.w.g.w.WebSocketExceptionHandler - "
                "[WS_EXCEPTION] Api=HeartRateWebSocketController.sendHeartRate Destination=/app/heart-rate/send-single "
                "Actor=USER#34 ExceptionType=org.springframework.dao.QueryTimeoutException Message=timeout memberId=34 "
                "StackTrace=com.widyu.heart.application.HeartRateService.processHeartRate(HeartRateService.java:51)")
        labels = {"kind": "ws", "api": "HeartRateWebSocketController.sendHeartRate",
                  "destination": "/app/heart-rate/send-single", "exceptionType": "QueryTimeoutException"}
        parsed = relay.parse_line("ws", line)
        self.assertEqual((parsed["actor"], parsed["message"]), ("USER#34", "timeout memberId=<id>"))
        embed = relay.build_embed("ws", labels, {"count": 1, "items": [parsed]}, "")
        self.assertEqual(embed["title"], "🔌 [DEV] WebSocket 실패 · /app/heart-rate/send-single")
        self.assertIn("👤 회원 #34", embed["description"])
        self.assertEqual(embed["color"], relay.RED)
        prompt = relay.build_prompt("ws", labels, parsed, [], "배포 커밋 확인 실패")
        self.assertIn("destination: /app/heart-rate/send-single", prompt)
        self.assertNotIn("USER#34", prompt)
        self.assertIn("ws", relay.AI_KINDS)

    def test_push_embed_shows_related_member_to_recipient(self):
        line = ("... ERROR traceId=none [w] c.w.f.a.FcmOutboxTransactions - [FCM_FAILED] Type=SAFE_ZONE_EXITED "
                "Emergency=true Related=34 Recipient=12 Attempts=4 Reason=INVALID_TOKEN")
        labels = {"kind": "push", "type": "SAFE_ZONE_EXITED", "emergency": "true", "reason": "INVALID_TOKEN"}
        embed = relay.build_embed("push", labels, {"count": 1, "items": [relay.parse_line("push", line)]}, "")
        self.assertEqual(embed["title"], "📵 [DEV] 푸시 알림 발송 실패 · 안심구역 이탈 (긴급)")
        self.assertIn("👤 회원 #34 → 회원 #12", embed["description"])

    def test_prompt_excludes_member_ids_trace_and_real_uri(self):
        parsed = relay.parse_line("api", SAMPLE_API_LINE)
        prompt = relay.build_prompt("api", API_LABELS, parsed, [], "배포 커밋 확인 실패")
        for secret in ("USER#12", "sessionId=abc", "3f2a9c1e", "01012345678", "kim@x.com", "uploads/abc"):
            self.assertNotIn(secret, prompt)

    def test_embed_lengths_stay_within_discord_limits(self):
        long = relay.with_analysis({"title": "t", "description": "x" * 4000}, "원인 추정: z" * 50)
        self.assertLessEqual(len(long["description"]), relay.DESCRIPTION_LIMIT)
        self.assertLessEqual(len(relay.build_embed("api", {"api": "가" * 400}, {"count": 0, "items": []}, "")["title"]),
                             relay.TITLE_LIMIT)


class QueueTest(StoreCase):
    def test_duplicate_webhook_is_processed_once_and_repeat_after_hours_is_new(self):
        alert = {"fingerprint": "f1", "startsAt": "2026-10-12T01:00:00Z", "labels": API_LABELS}
        self.assertTrue(self.store.enqueue(relay.dedupe_key(alert, 1000.0), alert, 1000.0))
        self.assertFalse(self.store.enqueue(relay.dedupe_key(alert, 1100.0), alert, 1100.0))
        self.assertTrue(self.store.enqueue(relay.dedupe_key(alert, 1000.0 + 4 * 3600), alert, 1000.0 + 4 * 3600))

    def test_daily_quota_survives_restart(self):
        self.assertEqual([self.store.take_quota("2026-10-12", 2) for _ in range(3)], [True, True, False])
        restarted = relay.Store(self.path)
        self.assertFalse(restarted.take_quota("2026-10-12", 2))
        restarted.close()

    def test_same_error_jobs_in_a_row_call_ai_once(self):
        # 워커는 작업을 하나씩 처리하므로 같은 오류가 동시에 들어와도 두 번째는 첫 분석을 재사용한다.
        details = {"count": 1, "items": [item()]}
        alert = {"labels": API_LABELS, "annotations": {}}
        with mock.patch.object(relay, "fetch_details", return_value=details), \
                mock.patch.object(relay, "send_discord", return_value="m1"), \
                mock.patch.object(relay, "edit_discord") as edit, \
                mock.patch.dict(os.environ, {"ANTHROPIC_API_KEY": "test"}):
            for fingerprint in ("f1", "f2"):
                self.store.enqueue(fingerprint, dict(alert, fingerprint=fingerprint), 1000.0)
            for _ in range(4):
                relay.process(self.store, self.store.next_job(2000.0), 2000.0)
        self.assertEqual(self.claude.call_count, 1)
        self.assertEqual(edit.call_count, 2)
        self.assertIn("분석 재사용", edit.call_args_list[1].args[1]["description"])

    def test_basic_alert_is_sent_even_when_analysis_storage_is_broken(self):
        details = {"count": 1, "items": [item()]}
        broken = mock.Mock(side_effect=sqlite3.OperationalError("disk I/O error"))
        with mock.patch.object(relay, "fetch_details", return_value=details), \
                mock.patch.object(relay, "send_discord", return_value="m1") as send, \
                mock.patch.object(self.store, "latest_analysis", broken), \
                mock.patch.object(self.store, "record_analysis", broken):
            self.store.enqueue("f1", {"labels": API_LABELS, "annotations": {}}, 1000.0)
            relay.process(self.store, self.store.next_job(2000.0), 2000.0)
            analysis = relay.analyze(self.store, "api", API_LABELS, item(), 2000.0)
        send.assert_called_once()
        self.assertEqual(analysis, "원인 추정: 테스트")


class AnalysisReuseTest(StoreCase):
    def analyze(self, now=2000.0, **kwargs):
        return relay.analyze(self.store, "api", API_LABELS, item(**kwargs), now)

    def test_same_error_reuses_saved_analysis_after_restart(self):
        self.analyze()
        self.store.close()
        self.store = relay.Store(self.path)
        reused = self.analyze(now=3000.0)
        self.assertEqual(self.claude.call_count, 1)
        self.assertIn("분석 재사용(커밋 aaaaaaaa", reused)

    def test_new_deploy_without_related_change_reuses(self):
        self.analyze()
        with mock.patch.object(relay, "deployed_sha", return_value=SHA_B), \
                mock.patch.object(relay, "changed_files", return_value=["admin/src/App.tsx", "docs/x.md"]):
            self.analyze(now=3000.0)
        self.assertEqual(self.claude.call_count, 1)

    def test_change_to_any_stack_file_or_config_triggers_reanalysis(self):
        for changed in (FRAME_FILE, "backend/widyu-api/src/main/resources/application-dev.yml",
                        "backend/widyu-api/build.gradle"):
            with self.subTest(changed=changed):
                self.claude.reset_mock()
                self.analyze()
                with mock.patch.object(relay, "deployed_sha", return_value=SHA_B), \
                        mock.patch.object(relay, "changed_files", return_value=[changed]):
                    self.analyze(now=3000.0)
                self.assertEqual(self.claude.call_count, 2)
                self.store.invalidate("", 3001.0)

    def test_unverifiable_change_does_not_reuse(self):
        self.analyze()
        with mock.patch.object(relay, "deployed_sha", return_value=SHA_B), \
                mock.patch.object(relay, "changed_files", return_value=None):
            self.analyze(now=3000.0)
        with mock.patch.object(relay, "deployed_sha", return_value=None):
            self.analyze(now=3001.0)
        self.assertEqual(self.claude.call_count, 3)

    def test_different_message_shape_is_different_cause_but_long_ids_are_not(self):
        self.analyze(message="처리 실패 status=503 request 1234567")
        self.analyze(now=3000.0, message="처리 실패 status=503 request 7654321")
        self.analyze(now=3001.0, message="처리 실패 status=401")
        self.assertEqual(self.claude.call_count, 2)

    def test_reuse_window_expiry_reanalyzes_and_keeps_history(self):
        self.analyze()
        self.analyze(now=2000.0 + relay.AI_REUSE_DAYS * 86400 + 1)
        self.assertEqual(self.claude.call_count, 2)
        count = self.store.db.execute("SELECT COUNT(*) FROM ai_analyses").fetchone()[0]
        self.assertEqual(count, 2)

    def test_manual_invalidation_forces_reanalysis(self):
        self.analyze()
        self.assertEqual(self.store.invalidate("푸시 결과 반영", 2500.0), 1)
        self.analyze(now=3000.0)
        self.assertEqual(self.claude.call_count, 2)

    def test_failed_empty_or_refused_answers_are_not_saved(self):
        failures = [relay.sys.modules["anthropic"].APIConnectionError("timeout"), None]
        self.claude.side_effect = failures + ["원인 추정: 정상"]
        self.assertIn("분석 실패", self.analyze())
        self.assertIn("분석 실패", self.analyze(now=2001.0))
        self.assertEqual(self.analyze(now=2002.0), "원인 추정: 정상")
        self.assertEqual(self.claude.call_count, 3)
        rows = self.store.db.execute("SELECT analysis, sha, prompt_version FROM ai_analyses").fetchall()
        self.assertEqual([tuple(row) for row in rows], [("원인 추정: 정상", SHA_A, relay.PROMPT_VERSION)])

    def test_backup_copies_history(self):
        self.analyze()
        target = os.path.join(self.directory.name, "backup.sqlite3")
        self.store.backup(target)
        restored = sqlite3.connect(target).execute("SELECT COUNT(*) FROM ai_analyses").fetchone()[0]
        self.assertEqual(restored, 1)


class ChangeDetectionTest(unittest.TestCase):
    def test_relevant_paths_cover_every_app_frame_in_both_modules(self):
        stack = ("com.widyu.a.A.x(A.java:1)\ncom.widyu.b.B$$SpringCGLIB$$0.y(<generated>)\n"
                 "org.springframework.Z.z(Z.java:3)")
        paths = relay.relevant_paths(stack)
        self.assertIn("backend/widyu-api/src/main/java/com/widyu/a/A.java", paths)
        self.assertIn("backend/widyu-domain/src/main/java/com/widyu/b/B.java", paths)
        self.assertEqual(len(paths), 4)

    def test_compare_result_is_unknown_when_truncated(self):
        body = json.dumps({"files": [{"filename": f"f{i}"} for i in range(relay.COMPARE_FILE_LIMIT)]}).encode()
        response = mock.MagicMock()
        response.__enter__.return_value.read.return_value = body
        with mock.patch.object(relay.urllib.request, "urlopen", return_value=response):
            self.assertIsNone(relay.changed_files(SHA_A, SHA_B))


if __name__ == "__main__":
    unittest.main()
