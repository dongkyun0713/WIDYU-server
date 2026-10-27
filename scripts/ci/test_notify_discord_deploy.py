"""배포 알림 스크립트를 전송 없이(DRY_RUN) 실행해 메시지 모양과 건너뛰기 조건을 확인한다."""
import json
import os
from pathlib import Path
import subprocess
import unittest

SCRIPT = Path(__file__).resolve().parent / "notify-discord-deploy.sh"


def run(**env):
    base = {"PATH": os.environ["PATH"], "DRY_RUN": "1", "COMMIT_SHA": "bb1d3dcb67809c73", "ACTOR": "dev"}
    return subprocess.run(["bash", str(SCRIPT)], env={**base, **env}, capture_output=True, text=True, check=True)


class NotifyDiscordDeployTest(unittest.TestCase):
    def test_lifecycle_messages_describe_confirmed_app_state(self):
        cases = {
            "app_stopped": ("🟠 [DEV] 서버 배포로 인한 앱 중단", "기존 앱 컨테이너를 중지"),
            "app_restarted": ("🟢 [DEV] 앱 재가동 완료", "HTTPS 헬스체크를 통과"),
            "app_rollback": ("🟡 [DEV] 이전 버전 앱 재가동 완료", "이전 버전으로 복구"),
        }
        for result, (title, detail) in cases.items():
            with self.subTest(result=result):
                payload = json.loads(run(RESULT=result, ENV_TAG="DEV", NOTIFY_SUCCESS="false").stdout)
                self.assertEqual(payload["embeds"][0]["title"], title)
                self.assertIn(detail, payload["embeds"][0]["description"])
                self.assertEqual(payload["allowed_mentions"], {"parse": []})

    def test_success_can_be_suppressed_without_suppressing_failure(self):
        self.assertEqual(run(RESULT="success", ENV_TAG="DEV", NOTIFY_SUCCESS="false").stdout.strip(),
                         "Deploy notification skipped: success")
        payload = json.loads(run(RESULT="failure", ENV_TAG="DEV", NOTIFY_SUCCESS="false").stdout)
        self.assertIn("실패", payload["embeds"][0]["title"])

    def test_success_message_shows_commit_actor_and_run_link(self):
        payload = json.loads(run(RESULT="success", ENV_TAG="DEV", COMMIT_MESSAGE="fix: 제목 @everyone\n\n본문",
                                 RUN_URL="https://github.com/x/actions/runs/1").stdout)
        embed = payload["embeds"][0]
        self.assertEqual(embed["title"], "✅ [DEV] 배포 완료")
        self.assertEqual(embed["description"].splitlines(),
                         ["fix: 제목 @everyone", "bb1d3dc · dev", "🔗 [실행 기록 보기](https://github.com/x/actions/runs/1)"])
        self.assertEqual(payload["allowed_mentions"], {"parse": []})

    def test_failure_message_names_failed_step(self):
        payload = json.loads(run(RESULT="failure", ENV_TAG="PROD", FAILED_STEP="서버 배포 단계", COMMIT_MESSAGE="x").stdout)
        self.assertEqual(payload["embeds"][0]["title"], "❌ [PROD] 배포 실패 · 서버 배포 단계")

    def test_cancelled_deploy_is_not_notified(self):
        self.assertEqual(run(RESULT="cancelled").stdout.strip(), "Deploy notification skipped: cancelled")

    def test_missing_webhook_does_not_fail_deploy(self):
        result = run(RESULT="success", DRY_RUN="", COMMIT_MESSAGE="x")
        self.assertIn("skipping deploy notification", result.stdout)


if __name__ == "__main__":
    unittest.main()
