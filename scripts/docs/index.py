#!/usr/bin/env python3
"""LLD·ADR 인덱스 표를 문서 헤더에서 생성한다.

인덱스를 README에 손으로 적어 두면, 여러 PR이 표의 같은 위치에 행을 추가할 때
Git이 인접 라인 추가를 자동 병합하지 못해 충돌이 반복된다. 표를 커밋하지 않고
필요할 때 문서 헤더에서 만들어 쓰면 충돌 원인 자체가 사라진다.

사용법:
    python3 scripts/docs/index.py          # LLD·ADR 모두
    python3 scripts/docs/index.py lld
    python3 scripts/docs/index.py adr
"""

import re
import sys
from pathlib import Path

DOCS_ROOT = Path(__file__).resolve().parents[2] / "docs"

# kind -> (파일 접두어, 상태 옆에 함께 보여줄 헤더 항목)
KINDS = {
    "lld": ("LLD", "Issue"),
    "adr": ("ADR", "날짜"),
}

MISSING = "-"


def header_of(text):
    """첫 `## ` 섹션 앞까지가 문서 헤더다.

    본문에도 표가 많아(인수조건, 에러 코드 등) 검색 범위를 넓히면 헤더에 항목을
    빠뜨린 문서에서 본문 값을 헤더 값으로 착각한다.
    """
    section = re.search(r"^##\s", text, re.M)
    if not section:
        return text
    return text[: section.start()]


def read_field(header, name):
    """헤더의 `| 이름 | 값 |` 또는 `- 이름: 값` 중 처음 것을 읽는다.

    ADR-0012·ADR-0016은 리스트 형식이고 나머지는 표 형식이라 둘 다 받는다.
    """
    text = header
    row = re.search(rf"^\|\s*{re.escape(name)}\s*\|\s*(.*?)\s*\|\s*$", text, re.M)
    if row and row.group(1):
        return row.group(1)
    item = re.search(rf"^-\s*{re.escape(name)}\s*:\s*(\S.*?)\s*$", text, re.M)
    if item:
        return item.group(1)
    return MISSING


def read_title(text):
    """첫 H1 `# LLD-0039: 제목`에서 제목만 떼어 낸다."""
    heading = re.search(r"^#\s+(.+?)\s*$", text, re.M)
    if not heading or ":" not in heading.group(1):
        return MISSING
    return heading.group(1).split(":", 1)[1].strip()


def parse(path, kind):
    prefix, extra = KINDS[kind]
    header = header_of(path.read_text(encoding="utf-8"))
    return {
        "number": re.match(rf"{prefix}-(\d+)", path.name).group(1),
        "file": path.name,
        "title": read_title(header),
        "status": read_field(header, "상태"),
        "extra": read_field(header, extra),
    }


def collect(kind, docs_root=DOCS_ROOT):
    """번호 내림차순(같은 번호는 파일명 오름차순)으로 문서를 모은다."""
    prefix = KINDS[kind][0]
    entries = [parse(p, kind) for p in (docs_root / kind).glob(f"{prefix}-*.md")]
    entries.sort(key=lambda e: (-int(e["number"]), e["file"]))
    return entries


def escape_cell(value):
    """표 안에서 파이프가 열을 쪼개지 않게 막는다. 백슬래시를 먼저 바꾼다."""
    return value.replace("\\", "\\\\").replace("|", "\\|")


def render(kind, entries):
    prefix, extra = KINDS[kind]
    lines = [f"| 번호 | 제목 | 상태 | {extra} |", "| --- | --- | --- | --- |"]
    for e in entries:
        cells = " | ".join(escape_cell(e[key]) for key in ("title", "status", "extra"))
        lines.append(f"| [{prefix}-{e['number']}]({e['file']}) | {cells} |")
    return "\n".join(lines)


def duplicates(entries):
    """번호를 나눠 쓰는 문서들. 번호 선점 경합의 흔적이라 경고만 남긴다."""
    by_number = {}
    for e in entries:
        by_number.setdefault(e["number"], []).append(e["file"])
    return {n: files for n, files in by_number.items() if len(files) > 1}


def main(argv):
    kinds = argv or list(KINDS)
    for kind in kinds:
        if kind not in KINDS:
            print(f"알 수 없는 문서 종류: {kind} (lld 또는 adr)", file=sys.stderr)
            return 2
    for kind in kinds:
        entries = collect(kind, DOCS_ROOT)
        if len(kinds) > 1:
            print(f"## {KINDS[kind][0]}\n")
        print(render(kind, entries))
        print()
        for number, files in sorted(duplicates(entries).items()):
            print(f"[경고] {KINDS[kind][0]}-{number} 번호를 {len(files)}개 문서가 나눠 씁니다: "
                  + ", ".join(files), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
