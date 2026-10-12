"""Grafana 알림을 받아 Discord에 기능 이름 중심 알림을 보내고, 같은 메시지에 Claude 분석을 덧붙인다(LLD-0084).

흐름
  1. Grafana webhook을 SQLite 작업으로 저장한 뒤 200을 응답한다. 저장에 실패하면 500을 돌려 Grafana가 재시도한다.
  2. 워커 하나가 작업을 순서대로 처리한다(동시 AI 호출 1개).
     - 알림 레이블은 기능·작업 같은 식별 정보뿐이다. traceId·요청자·대상·메시지·스택은 Loki에서 대표 로그를 다시 조회한다.
     - Discord 기본 알림을 보낸다(?wait=true로 message id 저장).
     - 오류 시점의 배포 커밋([BUILD] Version=)으로 소스를 가져와 Claude를 1회 호출하고 같은 메시지를 PATCH한다.
  3. 실패한 단계는 지수 백오프로 재시도한다. 하루 AI 호출 수와 분석 이력도 SQLite에 있어 재시작해도 유지된다.
     분석은 이력으로 계속 보관하고, 같은 원인이면 재사용 기간(기본 7일) 안에서만 다시 쓴다.

Discord에는 내부 회원 ID와 역할만, Claude에는 회원 ID·traceId·실제 URI 없이 마스킹한 예외·스택·코드만 보낸다.

    python relay.py                       # 서버 실행 (포트 8080)
    python relay.py --dry-run FILE        # Grafana payload JSON으로 만든 Discord JSON 출력(Loki 조회 없음)
    python relay.py --invalidate PATTERN  # 키에 PATTERN이 든 저장 분석을 무효화 → 다음 발생 때 재분석
    python relay.py --backup PATH         # 실행 중에도 일관된 SQLite 백업 파일을 만든다
    python -m unittest test_relay         # 외부 호출 없이 핵심 로직 확인
"""

import datetime
import hashlib
import json
import os
import re
import sqlite3
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DISCORD_WEBHOOK_URL = os.environ.get("DISCORD_WEBHOOK_URL", "")
LOKI_URL = os.environ.get("LOKI_URL", "http://loki:3100")
LOKI_SELECTOR = os.environ.get("LOKI_SELECTOR", '{service="widyu-api-dev", environment="dev"}')
SOURCE_REPO = os.environ.get("ALERT_SOURCE_REPO", "GB-able/WIDYU-server")
SOURCE_RAW_BASE = f"https://raw.githubusercontent.com/{SOURCE_REPO}"
GITHUB_API = "https://api.github.com"
DB_PATH = os.environ.get("ALERT_DB_PATH", "/data/relay.sqlite3")
AI_MODEL = os.environ.get("ALERT_AI_MODEL", "claude-opus-5")
AI_DAILY_LIMIT = int(os.environ.get("ALERT_AI_DAILY_LIMIT", "50"))
AI_REUSE_DAYS = float(os.environ.get("ALERT_AI_REUSE_DAYS", "7"))
ENV_TAG = os.environ.get("ALERT_ENV_TAG", "DEV")

# 분석 규칙(시스템 프롬프트·입력 형식)을 바꾸면 올린다. 분석 키에 들어가 이전 분석을 재사용하지 않게 된다.
PROMPT_VERSION = "1"
DETAIL_WINDOW_MINUTES = 15
DETAIL_LIMIT = 200
MAX_ITEMS_PER_MESSAGE = 3
MAX_ATTEMPTS = 6
TITLE_LIMIT = 256
DESCRIPTION_LIMIT = 4096
RED, ORANGE = 0xE5484D, 0xF5A524
AI_KINDS = {"api", "scheduler", "error"}
MODULE_SOURCE_ROOTS = ("backend/widyu-api/src/main/java/", "backend/widyu-domain/src/main/java/")
# 코드가 아니어도 원인에 영향을 주는 파일. 저장 분석 이후 이 중 하나라도 바뀌면 재분석한다.
CONFIG_PATH_PREFIXES = ("backend/widyu-api/src/main/resources/", "backend/widyu-domain/src/main/resources/")
CONFIG_FILES = {"build.gradle", "settings.gradle", "backend/build.gradle",
                "backend/widyu-api/build.gradle", "backend/widyu-domain/build.gradle"}
COMPARE_FILE_LIMIT = 300
SHA = re.compile(r"^[0-9a-f]{7,40}$")
FRAME = re.compile(r"(com\.widyu\.[\w.$]+)\.([\w$<>]+)\((\w+\.java):(\d+)\)")
STACK_CLASS = re.compile(r"(com\.widyu\.[\w.$]+)\.[\w$<>]+\(")
TIMESTAMPED = re.compile(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}")

API_LINE = re.compile(
    r"traceId=(?P<traceId>\S+) .*\[EXCEPTION\] Time=\S+ Api=.+? Method=\S+ Path=\S+ Actor=(?P<actor>\S+) "
    r"Target=(?P<target>\S+) ExceptionType=(?P<exceptionType>\S+) Message=(?P<message>.*?) RequestURI=\S* "
    r"StackTrace=(?P<stack>.*)$")
SCHEDULER_LINE = re.compile(
    r"\[SCHEDULER\] Task=\S+ ExceptionType=(?P<exceptionType>\S+) Message=(?P<message>.*?)(?: StackTrace=(?P<stack>.*))?$")
PUSH_LINE = re.compile(r"\[FCM_FAILED\] .*Related=(?P<related>\S+) Recipient=(?P<recipient>\S+) Attempts=(?P<attempts>\S+)")
PLAIN_LINE = re.compile(r"ERROR traceId=(?P<traceId>\S+) \[[^\]]*\] \S+ - (?P<message>.*)$")

# 알림 종류 코드 → 사람이 읽는 이름. 없는 코드는 그대로 보여 준다.
PUSH_TYPE_NAMES = {
    "SAFE_ZONE_EXITED": "안심구역 이탈",
    "SAFE_ZONE_ENTERED": "안심구역 도착",
    "HEART_RATE_EMERGENCY": "심박 위급",
    "SAFETY_SELF_CHECK": "안전 자가 확인",
    "MEDICATION_PROOF_MISSING": "복약 인증 누락",
    "HEALTH_SCHEDULE_UPCOMING": "건강 일정 알림",
    "WALK_GOAL_UNMET": "걸음 목표 미달",
    "ALBUM_CREATED": "앨범 업로드",
    "ALBUM_COMMENTED": "앨범 댓글",
    "HEART_MESSAGE_RECEIVED": "마음 메시지",
}
PUSH_REASONS = {
    "INVALID_TOKEN": "만료·삭제된 기기 토큰",
    "REJECTED": "FCM이 거부",
    "RETRY_EXHAUSTED": "재시도 소진",
    "EXPIRED": "전송 기한 지남",
    "INVALID_PAYLOAD": "저장된 알림 데이터 손상",
}

SYSTEM_PROMPT = """너는 Spring Boot 백엔드(WIDYU)의 개발 서버 에러를 분석한다.
주어진 예외, 스택 프레임, 소스 조각만 근거로 원인을 추정한다. 근거가 부족하면 부족하다고 쓴다.
소스가 없다고 표시되면 코드 줄 내용을 추측하지 말고 스택의 위치만 근거로 쓴다.
입력의 <값>, <id> 같은 표시는 개인정보를 가린 자리다.
한국어로, 아래 네 줄 형식만 출력한다. 전체 700자 이내, 마크다운 제목·코드 블록 없이.
원인 추정: <한두 문장>
근거: <파일명:줄 — 그 줄이 하는 일>
확인할 것: <다음에 볼 로그·설정·데이터>
수정 방향: <한 문장>"""


# ---------- 마스킹 ----------

QUOTED = re.compile(r"(?i)(\b(?:key|constraint|table|column|index)\s+)?'([^']*)'")
MASKS = [
    (re.compile(r"(Actor|Target|traceId|RequestURI)=\S+"), r"\1=<제외>"),
    (re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+"), "<email>"),
    (re.compile(r"(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b"), "<uuid>"),
    (re.compile(r"(?i)\b(\w*id)([=:]\s*)\d+"), r"\1\2<id>"),
    (re.compile(r"(?i)\b(phone\w*|token|password|name|session\w*)([=:]\s*)[^\s,&)]+"), r"\1\2<값>"),
    (re.compile(r"\d[\d-]{6,}\d"), "<num>"),
]


def _quoted(match):
    # 제약조건·테이블·컬럼·인덱스 이름은 원인을 가르는 정보라 남기고, 나머지 따옴표 값(입력값)은 가린다.
    if match.group(1):
        return match.group(0)
    return "'<값>'"


def mask(text):
    """Discord·AI로 보내기 전에 개인정보와 요청마다 달라지는 값만 가린다.
    HTTP 상태(401·503), SQL 오류 코드(1062), 제약조건·테이블 이름처럼 원인을 가르는 값은 남긴다."""
    text = QUOTED.sub(_quoted, text)
    for pattern, replacement in MASKS:
        text = pattern.sub(replacement, text)
    return text


# ---------- 저장소 ----------

class Store:
    def __init__(self, path):
        self.lock = threading.Lock()
        self.db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS jobs (
                id INTEGER PRIMARY KEY, dedupe_key TEXT UNIQUE, alert TEXT NOT NULL,
                state TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0,
                next_at REAL NOT NULL, message_id TEXT, embed TEXT, analysis_input TEXT, created_at REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS ai_analyses (
                id INTEGER PRIMARY KEY, key TEXT NOT NULL, analysis TEXT NOT NULL, sha TEXT, model TEXT,
                prompt_version TEXT NOT NULL, created_at REAL NOT NULL, invalidated_at REAL);
            CREATE INDEX IF NOT EXISTS idx_ai_analyses_key ON ai_analyses (key, created_at);
            CREATE TABLE IF NOT EXISTS ai_quota (day TEXT PRIMARY KEY, count INTEGER NOT NULL);
        """)

    def enqueue(self, key, alert, now):
        with self.lock:
            cursor = self.db.execute(
                "INSERT OR IGNORE INTO jobs (dedupe_key, alert, next_at, created_at) VALUES (?, ?, ?, ?)",
                (key, json.dumps(alert, ensure_ascii=False), now, now))
            return cursor.rowcount == 1

    def next_job(self, now):
        with self.lock:
            return self.db.execute(
                "SELECT * FROM jobs WHERE state IN ('pending', 'sent') AND next_at <= ? ORDER BY id LIMIT 1",
                (now,)).fetchone()

    def update(self, job_id, **fields):
        columns = ", ".join(f"{name} = ?" for name in fields)
        with self.lock:
            self.db.execute(f"UPDATE jobs SET {columns} WHERE id = ?", (*fields.values(), job_id))

    def take_quota(self, day, limit):
        """하루 상한 안에서만 1 증가시킨다. 조건을 UPDATE 안에 넣어 재시작·동시 실행에도 넘지 않는다."""
        with self.lock:
            self.db.execute("INSERT OR IGNORE INTO ai_quota (day, count) VALUES (?, 0)", (day,))
            cursor = self.db.execute("UPDATE ai_quota SET count = count + 1 WHERE day = ? AND count < ?", (day, limit))
            return cursor.rowcount == 1

    def latest_analysis(self, key, since):
        """재사용 기간 안의 무효화되지 않은 가장 최근 분석. 기간이 지난 분석도 지우지 않고 이력으로 남는다."""
        with self.lock:
            return self.db.execute(
                "SELECT * FROM ai_analyses WHERE key = ? AND invalidated_at IS NULL AND created_at >= ? "
                "ORDER BY created_at DESC LIMIT 1", (key, since)).fetchone()

    def record_analysis(self, key, analysis, sha, now):
        with self.lock:
            self.db.execute(
                "INSERT INTO ai_analyses (key, analysis, sha, model, prompt_version, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                (key, analysis, sha, AI_MODEL, PROMPT_VERSION, now))

    def invalidate(self, pattern, now):
        with self.lock:
            cursor = self.db.execute(
                "UPDATE ai_analyses SET invalidated_at = ? WHERE invalidated_at IS NULL AND key LIKE ?",
                (now, f"%{pattern}%"))
            return cursor.rowcount

    def close(self):
        self.db.close()

    def backup(self, path):
        with self.lock:
            target = sqlite3.connect(path)
            try:
                self.db.backup(target)
            finally:
                target.close()

    def cleanup(self, now):
        with self.lock:
            # 분석 이력(ai_analyses)은 지우지 않는다. 처리 끝난 작업만 7일 뒤 정리한다.
            self.db.execute("DELETE FROM jobs WHERE state IN ('done', 'failed') AND created_at < ?", (now - 7 * 86400,))


def dedupe_key(alert, now):
    """Grafana가 같은 알림을 재전송해도 한 번만 처리한다. repeat_interval(4h) 재알림은 시간 구간이 달라 새로 보낸다."""
    bucket = int(now // (15 * 60))
    raw = f"{alert.get('fingerprint')}|{alert.get('startsAt')}|{bucket}"
    return hashlib.sha256(raw.encode()).hexdigest()


# ---------- Loki ----------

def loki(query, start_ns, end_ns, limit, direction="backward"):
    params = urllib.parse.urlencode({
        "query": query, "start": start_ns, "end": end_ns, "limit": limit, "direction": direction})
    with urllib.request.urlopen(f"{LOKI_URL}/loki/api/v1/query_range?{params}", timeout=15) as response:
        result = json.loads(response.read())["data"]["result"]
    values = [(int(ts), line) for stream in result for ts, line in stream["values"]]
    return sorted(values, reverse=direction == "backward")


def logql_text(value):
    return value.replace("\\", "\\\\").replace('"', '\\"')


def detail_query(kind, labels):
    if kind == "api":
        exception_type = re.escape(labels.get("exceptionType", "")).replace("\\", "\\\\")
        return (f'{LOKI_SELECTOR} |= "[EXCEPTION]" '
                f'|= " Method={logql_text(labels.get("method", ""))} Path={logql_text(labels.get("path", ""))} " '
                f'|~ "ExceptionType=([^ ]*\\\\.)?{exception_type} "')
    if kind == "scheduler" and labels.get("task"):
        return f'{LOKI_SELECTOR} |= "[SCHEDULER] Task={logql_text(labels["task"])} "'
    if kind == "scheduler":
        return f'{LOKI_SELECTOR} |= " ERROR traceId=none [scheduling-" |= "] {logql_text(labels.get("logger", ""))} - "'
    if kind == "push":
        return (f'{LOKI_SELECTOR} |= "[FCM_FAILED] Type={logql_text(labels.get("type", ""))} " '
                f'|= "Reason={logql_text(labels.get("reason", ""))}"')
    return (f'{LOKI_SELECTOR} |= " ERROR traceId=" |= "] {logql_text(labels.get("logger", ""))} - " '
            f'!~ "\\\\[EXCEPTION\\\\]|\\\\[FCM_FAILED\\\\]|\\\\[scheduling-"')


def parse_line(kind, line):
    """대표 로그 한 줄에서 알림·분석에 쓸 값을 꺼낸다. 메시지와 스택은 여기서 마스킹한다."""
    item = {"raw_stack": ""}
    match = None
    if kind == "api":
        match = API_LINE.search(line)
    elif kind == "push":
        match = PUSH_LINE.search(line)
    elif "[SCHEDULER]" in line:
        match = SCHEDULER_LINE.search(line)
    if match is None:
        match = PLAIN_LINE.search(line)
    if match:
        item.update({key: value for key, value in match.groupdict().items() if value is not None})
    if "message" in item:
        item["message"] = mask(item["message"]).replace("\\n", " ")
    if "stack" in item:
        item["raw_stack"] = mask(item.pop("stack").replace("\\n", "\n"))
    if item.get("exceptionType"):
        item["exceptionType"] = item["exceptionType"].rsplit(".", 1)[-1]
    return item


def continuation(start_ns):
    """한 줄 스택이 아닌 ERROR 로그는 바로 뒤에 이어진 스택 줄을 모은다."""
    lines = [line for _, line in loki(LOKI_SELECTOR, start_ns, start_ns + 2 * 10**9, 100, "forward")]
    collected = []
    for line in lines[1:]:
        if TIMESTAMPED.match(line):
            break
        collected.append(line)
    return mask("\n".join(collected))


def fetch_details(kind, labels, now):
    end_ns = int(now * 1e9)
    start_ns = end_ns - DETAIL_WINDOW_MINUTES * 60 * 10**9
    found = loki(detail_query(kind, labels), start_ns, end_ns, DETAIL_LIMIT)
    items = []
    for ts, line in found[:MAX_ITEMS_PER_MESSAGE]:
        item = parse_line(kind, line)
        item["ts"] = ts
        items.append(item)
    if items and kind in AI_KINDS and not items[0]["raw_stack"]:
        items[0]["raw_stack"] = continuation(items[0]["ts"])
    return {"count": len(found), "items": items}


def deployed_sha(ts_ns):
    """오류 시각 직전에 남은 [BUILD] Version= 줄의 커밋. 찾지 못하면 None — develop으로 대신하지 않는다."""
    lines = loki(f'{LOKI_SELECTOR} |= "[BUILD] Version="', ts_ns - 7 * 86400 * 10**9, ts_ns + 10**9, 1)
    if not lines:
        return None
    sha = lines[0][1].rsplit("Version=", 1)[-1].strip()
    if SHA.match(sha):
        return sha
    return None


# ---------- Discord 메시지 ----------

def truncate(text, limit):
    if len(text) <= limit:
        return text
    return text[: limit - 1] + "…"


def actor_text(actor):
    if not actor or actor == "anonymous":
        return "비로그인"
    role, _, member_id = actor.partition("#")
    if role == "ADMIN":
        return f"관리자 #{member_id}"
    return f"회원 #{member_id}"


def dashboard_link(base, item):
    if not base:
        return ""
    params = {}
    if item.get("traceId") and item["traceId"] != "none":
        params["var-traceId"] = item["traceId"]
    if item.get("ts"):
        ms = item["ts"] // 10**6
        params.update({"from": ms - 5 * 60_000, "to": ms + 60_000})
    if not params:
        return base
    separator = "&" if "?" in base else "?"
    return f"{base}{separator}{urllib.parse.urlencode(params)}"


def title_of(kind, labels):
    if kind == "api":
        return f"🚨 [{ENV_TAG}] {labels.get('api', '알 수 없는 API')} 실패"
    if kind == "scheduler":
        return f"⏰ [{ENV_TAG}] 스케줄러 실패 · {labels.get('task') or labels.get('logger', '')}"
    if kind == "push":
        name = PUSH_TYPE_NAMES.get(labels.get("type", ""), labels.get("type", ""))
        urgent = " (긴급)" if labels.get("emergency") == "true" else ""
        return f"📵 [{ENV_TAG}] 푸시 알림 발송 실패 · {name}{urgent}"
    return f"⚠️ [{ENV_TAG}] 에러 로그 · {labels.get('logger', '')}"


def item_lines(kind, labels, item, base_link):
    link = dashboard_link(base_link, item)
    if kind == "push":
        reason = PUSH_REASONS.get(labels.get("reason", ""), labels.get("reason", ""))
        who = f"👤 → 회원 #{item.get('recipient', '?')}"
        if item.get("related") not in (None, "null"):
            who = f"👤 회원 #{item['related']} → 회원 #{item.get('recipient', '?')}"
        return [who, f"💥 {reason} · {item.get('attempts', '?')}회 시도", f"🔗 [로그 보기]({link})"]
    lines = []
    if kind == "api":
        who = f"👤 {actor_text(item.get('actor'))}"
        if item.get("target") and item["target"] != "-":
            who += f" → {item['target']}"
        lines.append(who)
    error = item.get("message", "")
    exception_type = item.get("exceptionType") or labels.get("exceptionType")
    if exception_type:
        error = f"`{exception_type}` {error}"
    lines.append(f"💥 {error}")
    trace = ""
    if item.get("traceId") and item["traceId"] != "none":
        trace = f" · traceId `{item['traceId']}`"
    lines.append(f"🔗 [로그 보기]({link}){trace}")
    return lines


def build_embed(kind, labels, details, base_link):
    head = []
    if kind == "api":
        head.append(f"**{labels.get('method', '')} {labels.get('path', '')}**")
    blocks = ["\n".join(item_lines(kind, labels, item, base_link)) for item in details["items"]]
    if not blocks:
        blocks = [f"상세 로그를 찾지 못했습니다. 🔗 [로그 보기]({base_link})"]
    count = details["count"]
    summary = f"최근 {DETAIL_WINDOW_MINUTES}분 {count}건"
    if count >= DETAIL_LIMIT:
        summary = f"최근 {DETAIL_WINDOW_MINUTES}분 {DETAIL_LIMIT}건 이상"
    if count > len(details["items"]):
        summary += f" · 앞 {len(details['items'])}건 표시"
    description = "\n".join(head + ["\n\n".join(blocks), "", f"-# {summary}"])
    color = ORANGE
    if kind in ("api", "scheduler") or labels.get("emergency") == "true":
        color = RED
    return {
        "title": truncate(title_of(kind, labels), TITLE_LIMIT),
        "description": truncate(description, DESCRIPTION_LIMIT),
        "color": color,
        "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    }


def with_analysis(embed, analysis):
    updated = dict(embed)
    section = f"\n\n**🤖 AI 분석**\n{analysis}"
    room = DESCRIPTION_LIMIT - len(section)
    updated["description"] = truncate(truncate(embed["description"], max(room, 200)) + section, DESCRIPTION_LIMIT)
    updated["footer"] = {"text": "AI 분석은 로그와 코드로 추정한 결과이며 확정 원인이 아닙니다."}
    return updated


def discord_payload(embed):
    # AI 출력이나 예외 메시지가 @everyone 같은 멘션을 울리지 못하게 한다.
    return {"embeds": [embed], "allowed_mentions": {"parse": []}}


def discord_request(method, url, body):
    request = urllib.request.Request(url, data=json.dumps(body).encode(), method=method,
                                     headers={"Content-Type": "application/json", "User-Agent": "widyu-alert-relay"})
    with urllib.request.urlopen(request, timeout=10) as response:
        raw = response.read()
    if raw:
        return json.loads(raw)
    return {}


def send_discord(embed):
    return discord_request("POST", f"{DISCORD_WEBHOOK_URL}?wait=true", discord_payload(embed))["id"]


def edit_discord(message_id, embed):
    discord_request("PATCH", f"{DISCORD_WEBHOOK_URL}/messages/{message_id}", discord_payload(embed))


# ---------- AI 분석 ----------

def app_frames(stack, limit=5):
    frames, seen = [], set()
    for class_name, method, file_name, line in FRAME.findall(stack):
        outer = class_name.split("$")[0]
        if (outer, line) in seen:
            continue
        seen.add((outer, line))
        frames.append({"class": outer, "method": method, "file": file_name, "line": int(line)})
        if len(frames) == limit:
            break
    return frames


def source_paths(class_name):
    relative = class_name.replace(".", "/") + ".java"
    return [root + relative for root in MODULE_SOURCE_ROOTS]


def fetch_source(sha, class_name):
    for path in source_paths(class_name):
        try:
            with urllib.request.urlopen(f"{SOURCE_RAW_BASE}/{sha}/{path}", timeout=10) as response:
                return path, response.read().decode().splitlines()
        except urllib.error.HTTPError:
            continue
    return None


def snippet(path, lines, line_no, radius=20):
    start = max(1, line_no - radius)
    end = min(len(lines), line_no + radius)
    body = "\n".join(f"{n}{'>' if n == line_no else ' '} {lines[n - 1]}" for n in range(start, end + 1))
    return f"--- {path} (오류 줄 {line_no})\n{body}"


def message_shape(message):
    """같은 원인 판단용 메시지 형태. 이미 가린 값 외에 요청마다 달라지는 긴 숫자(6자리 이상)만 지운다.
    HTTP 상태(503), SQL 오류 코드(1062), SQLState(23000), 제약조건 이름은 그대로라 다른 원인과 섞이지 않는다."""
    return re.sub(r"\b\d{6,}\b", "<n>", message or "").strip()


def analysis_key(kind, labels, item, frames):
    """같은 원인 = 환경·종류·기능·예외 종류·메시지 형태·가장 안쪽 앱 프레임·분석 규칙 버전이 같은 오류.
    배포 커밋은 키에 넣지 않는다. 코드가 바뀌었는지는 재사용할 때 커밋 비교로 따로 확인한다."""
    top = ""
    if frames:
        top = f"{frames[0]['class']}:{frames[0]['line']}"
    subject = labels.get("api") or labels.get("task") or labels.get("logger") or ""
    exception_type = item.get("exceptionType") or labels.get("exceptionType", "")
    return "|".join([ENV_TAG, kind, subject, exception_type, message_shape(item.get("message")), top,
                     f"prompt-v{PROMPT_VERSION}"])


def relevant_paths(stack):
    """스택에 나온 모든 앱 클래스의 소스 경로(모듈 후보 둘 다)."""
    classes = {class_name.split("$")[0] for class_name in STACK_CLASS.findall(stack)}
    return {path for class_name in classes for path in source_paths(class_name)}


def is_relevant_change(path, paths):
    return path in paths or path in CONFIG_FILES or path.startswith(CONFIG_PATH_PREFIXES)


def changed_files(base_sha, head_sha):
    """두 커밋 사이 바뀐 파일. 실패하거나 목록이 잘리면(None) 바뀌었다고 본다."""
    request = urllib.request.Request(
        f"{GITHUB_API}/repos/{SOURCE_REPO}/compare/{base_sha}...{head_sha}",
        headers={"Accept": "application/vnd.github+json", "User-Agent": "widyu-alert-relay"})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            files = json.loads(response.read()).get("files", [])
    except (urllib.error.URLError, ValueError):
        return None
    if len(files) >= COMPARE_FILE_LIMIT:
        return None
    return [entry["filename"] for entry in files]


def reusable_analysis(store, key, sha, stack, now):
    """재사용 기간 안이고, 그 사이 관련 코드·설정이 바뀌지 않은 저장 분석만 돌려준다. 확인할 수 없으면 재사용하지 않는다."""
    row = store.latest_analysis(key, now - AI_REUSE_DAYS * 86400)
    if row is None or not sha or not row["sha"]:
        return None
    if row["sha"] == sha:
        return row
    changed = changed_files(row["sha"], sha)
    if changed is None:
        return None
    paths = relevant_paths(stack)
    if any(is_relevant_change(path, paths) for path in changed):
        return None
    return row


def build_prompt(kind, labels, item, sources, source_note):
    """AI 입력. 요청자·대상·traceId는 넣지 않고, 메시지·스택은 이미 마스킹한 값을 쓴다."""
    head = [f"알림 종류: {kind}"]
    for key in ("api", "method", "path", "task", "logger"):
        if labels.get(key):
            head.append(f"{key}: {labels[key]}")
    head.append(f"exceptionType: {item.get('exceptionType') or labels.get('exceptionType', '')}")
    head.append(f"message: {item.get('message', '')}")
    stack_lines = [line for line in item.get("raw_stack", "").splitlines() if line.strip()][:60]
    body = "\n".join(head) + "\n\n스택:\n" + ("\n".join(stack_lines) or "(없음)")
    if sources:
        return body + "\n\n소스:\n" + "\n\n".join(sources)
    return body + f"\n\n소스: 없음({source_note})"


def ask_claude(prompt):
    import anthropic  # 서버에서만 필요하다. 단위 테스트는 SDK 없이 돈다.

    # 짧은 진단이라 effort를 낮춰 지연·비용을 줄인다. thinking은 Opus 5 기본(adaptive)을 쓴다.
    client = anthropic.Anthropic(timeout=300.0, max_retries=1)
    response = client.beta.messages.create(
        model=AI_MODEL,
        max_tokens=16000,
        system=SYSTEM_PROMPT,
        messages=[{"role": "user", "content": prompt}],
        betas=["server-side-fallback-2026-07-01"],
        extra_body={"fallbacks": "default", "output_config": {"effort": "medium"}},
    )
    if response.stop_reason == "refusal":
        return None
    return "".join(block.text for block in response.content if block.type == "text").strip() or None


def analyze(store, kind, labels, item, now):
    """분석 문구를 돌려준다. 정상 분석만 이력에 저장하고, 실패·거절·빈 답변은 저장하지 않아 다음에 다시 분석한다."""
    import anthropic

    stack = item.get("raw_stack", "")
    frames = app_frames(stack)
    try:
        sha = deployed_sha(item.get("ts") or int(now * 1e9))
    except (urllib.error.URLError, KeyError, ValueError):
        sha = None
    key = analysis_key(kind, labels, item, frames)
    try:
        reused = reusable_analysis(store, key, sha, stack, now)
    except sqlite3.Error:
        reused = None  # 저장소 장애는 재사용만 포기하고 분석은 계속한다.
    if reused is not None:
        day = datetime.datetime.fromtimestamp(reused["created_at"]).strftime("%Y-%m-%d")
        return f"{reused['analysis']}\n-# {day} 분석 재사용(커밋 {(reused['sha'] or '?')[:8]}, 이후 관련 코드·설정 변경 없음)"
    sources, note = [], "배포 커밋 확인 실패"
    if sha:
        note = f"커밋 {sha[:8]}에서 파일을 찾지 못함"
        for frame in frames:
            found = fetch_source(sha, frame["class"])
            if found:
                sources.append(snippet(found[0], found[1], frame["line"]))
    if not store.take_quota(datetime.date.today().isoformat(), AI_DAILY_LIMIT):
        return f"오늘 분석 한도({AI_DAILY_LIMIT}회)를 넘어 생략했습니다."
    try:
        analysis = ask_claude(build_prompt(kind, labels, item, sources, note))
    except anthropic.RateLimitError:
        return "분석 실패: Claude API 호출 한도 초과"
    except anthropic.APIStatusError as error:
        return f"분석 실패: Claude API 오류({error.status_code})"
    except anthropic.APIConnectionError:
        return "분석 실패: Claude API 연결 실패(시간 초과 포함)"
    except anthropic.APIError as error:
        # 작업 재시도로 넘기면 재시도마다 하루 호출 수를 다시 쓰므로 여기서 끝낸다.
        return f"분석 실패: Claude API 응답 오류({type(error).__name__})"
    if analysis is None:
        return "분석 실패: 빈 답변 또는 모델 정책상 거절"
    if not sources:
        analysis = f"⚠️ 소스 조회 실패({note}) — 스택 위치만으로 추정했습니다.\n{analysis}"
    try:
        store.record_analysis(key, analysis, sha, now)
    except sqlite3.Error:
        pass  # 저장 실패는 이번 알림에 영향을 주지 않는다. 다음 발생 때 다시 분석할 뿐이다.
    return analysis


# ---------- 작업 처리 ----------

def process(store, job, now):
    alert = json.loads(job["alert"])
    labels = alert.get("labels", {})
    kind = labels.get("kind", "error")
    base_link = alert.get("annotations", {}).get("grafana_url", "")
    if job["state"] == "pending":
        # 재시도로 처리가 늦어져도 알림을 받은 시각 기준 15분 로그를 본다.
        details = fetch_details(kind, labels, job["created_at"])
        embed = build_embed(kind, labels, details, base_link)
        message_id = send_discord(embed)
        item = (details["items"] or [{}])[0]
        next_state = "done"
        if kind in AI_KINDS and os.environ.get("ANTHROPIC_API_KEY") and details["items"]:
            next_state = "sent"
        store.update(job["id"], state=next_state, message_id=message_id, attempts=0, next_at=now,
                     embed=json.dumps(embed, ensure_ascii=False), analysis_input=json.dumps(item, ensure_ascii=False))
        print(f"sent job={job['id']} kind={kind} message={message_id}", flush=True)
        return
    embed = json.loads(job["embed"])
    item = json.loads(job["analysis_input"])
    edit_discord(job["message_id"], with_analysis(embed, analyze(store, kind, labels, item, now)))
    store.update(job["id"], state="done")


def run_worker(store):
    last_cleanup = 0.0
    while True:
        now = time.time()
        if now - last_cleanup > 3600:
            store.cleanup(now)
            last_cleanup = now
        job = store.next_job(now)
        if job is None:
            time.sleep(1)
            continue
        try:
            process(store, job, now)
        except Exception as error:  # 한 작업의 실패가 워커를 멈추지 않게 하고 백오프로 재시도한다.
            attempts = job["attempts"] + 1
            state = job["state"]
            if attempts >= MAX_ATTEMPTS:
                state = "failed"
            store.update(job["id"], attempts=attempts, state=state, next_at=now + min(30 * 2 ** attempts, 1800))
            print(f"job={job['id']} attempt={attempts} error={type(error).__name__}", file=sys.stderr, flush=True)


class Handler(BaseHTTPRequestHandler):
    store = None

    def do_GET(self):
        self.send_response(200 if self.path == "/health" else 404)
        self.end_headers()

    def do_POST(self):
        if self.path != "/grafana":
            self.send_response(404)
            self.end_headers()
            return
        try:
            payload = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        except ValueError:
            self.send_response(400)
            self.end_headers()
            return
        try:
            now = time.time()
            for alert in payload.get("alerts", []):
                if alert.get("status") == "firing":
                    self.store.enqueue(dedupe_key(alert, now), alert, now)
        except sqlite3.Error:
            # 저장하지 못했으면 실패로 응답해 Grafana가 다시 보내게 한다.
            self.send_response(500)
            self.end_headers()
            return
        self.send_response(200)
        self.end_headers()

    def log_message(self, *args):
        pass


# ---------- 운영 명령 ----------

def dry_run(path):
    with open(path, encoding="utf-8") as file:
        payload = json.load(file)
    for alert in payload.get("alerts", []):
        if alert.get("status") != "firing":
            continue
        labels = alert.get("labels", {})
        kind = labels.get("kind", "error")
        details = {"count": 0, "items": []}
        print(json.dumps(discord_payload(build_embed(kind, labels, details, "")), ensure_ascii=False, indent=2))


def main():
    if "--invalidate" in sys.argv:
        count = Store(DB_PATH).invalidate(sys.argv[sys.argv.index("--invalidate") + 1], time.time())
        print(f"invalidated {count} analyses")
        return
    if "--backup" in sys.argv:
        Store(DB_PATH).backup(sys.argv[sys.argv.index("--backup") + 1])
        print("backup done")
        return
    if "--dry-run" in sys.argv:
        dry_run(sys.argv[sys.argv.index("--dry-run") + 1])
        return
    if not DISCORD_WEBHOOK_URL:
        sys.exit("DISCORD_WEBHOOK_URL is required")
    store = Store(DB_PATH)
    Handler.store = store
    threading.Thread(target=run_worker, args=(store,), daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()


if __name__ == "__main__":
    main()
