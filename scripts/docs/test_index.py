"""scripts/docs/index.py 파서 검증."""

import io
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import index  # noqa: E402

REPO_DOCS = Path(__file__).resolve().parents[2] / "docs"

TABLE_HEADER = """# LLD-0042: 표 형식 문서

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #100 |
| 작성자 | tester |

## 1. 목적
"""

LIST_HEADER = """# ADR-0042: 리스트 형식 문서

- 상태: Accepted
- 날짜: 2026-09-18

## 맥락
"""


def write(root, kind, name, text):
    directory = root / kind
    directory.mkdir(parents=True, exist_ok=True)
    (directory / name).write_text(text, encoding="utf-8")


class ParseTest(unittest.TestCase):
    def test_표_형식_헤더에서_제목과_상태와_이슈를_읽는다(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(root, "lld", "LLD-0042-table.md", TABLE_HEADER)

            entry = index.collect("lld", root)[0]

            self.assertEqual("0042", entry["number"])
            self.assertEqual("표 형식 문서", entry["title"])
            self.assertEqual("Approved", entry["status"])
            self.assertEqual("#100", entry["extra"])

    def test_리스트_형식_헤더에서도_상태와_날짜를_읽는다(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(root, "adr", "ADR-0042-list.md", LIST_HEADER)

            entry = index.collect("adr", root)[0]

            self.assertEqual("Accepted", entry["status"])
            self.assertEqual("2026-09-18", entry["extra"])

    def test_헤더에_없는_항목은_하이픈으로_채운다(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(root, "lld", "LLD-0043-bare.md", "# LLD-0043: 헤더 없는 문서\n\n## 1. 목적\n")

            entry = index.collect("lld", root)[0]

            self.assertEqual("헤더 없는 문서", entry["title"])
            self.assertEqual("-", entry["status"])
            self.assertEqual("-", entry["extra"])

    def test_본문에_같은_이름이_또_나와도_헤더_값을_쓴다(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(root, "lld", "LLD-0044-dup.md",
                  TABLE_HEADER + "\n| 상태 | 본문에 적힌 다른 값 |\n")

            self.assertEqual("Approved", index.collect("lld", root)[0]["status"])

    def test_헤더에_없이_본문에만_있는_값은_읽지_않는다(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(root, "lld", "LLD-0045-body-only.md",
                  "# LLD-0045: 헤더에 상태가 없는 문서\n\n## 7. 인수조건\n\n"
                  "| 상태 | 본문 표의 값 |\n| --- | --- |\n")

            entry = index.collect("lld", root)[0]

            self.assertEqual("-", entry["status"])

    def test_파이프가_든_값은_표를_쪼개지_않게_막는다(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(root, "lld", "LLD-0046-pipe.md",
                  "# LLD-0046: 정렬 | 필터 정책\n\n| 항목 | 값 |\n| --- | --- |\n"
                  "| 상태 | Approved |\n")

            rendered = index.render("lld", index.collect("lld", root))

            self.assertIn(r"정렬 \| 필터 정책", rendered)
            # 이스케이프가 없으면 제목의 파이프가 열을 하나 더 만든다
            self.assertEqual(4, len(rendered.splitlines()[2].split(" | ")))

    def test_번호_내림차순으로_정렬한다(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(root, "lld", "LLD-0009-old.md", "# LLD-0009: 예전 문서\n")
            write(root, "lld", "LLD-0040-new.md", "# LLD-0040: 최근 문서\n")

            numbers = [e["number"] for e in index.collect("lld", root)]

            self.assertEqual(["0040", "0009"], numbers)

    def test_같은_번호를_쓰는_문서를_모두_내보내고_경고한다(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(root, "lld", "LLD-0021-a.md", "# LLD-0021: 먼저 쓴 문서\n")
            write(root, "lld", "LLD-0021-b.md", "# LLD-0021: 나중에 쓴 문서\n")

            entries = index.collect("lld", root)

            self.assertEqual(2, len(entries))
            self.assertEqual({"0021": ["LLD-0021-a.md", "LLD-0021-b.md"]},
                             index.duplicates(entries))

    def test_표는_문서_경로를_링크로_건다(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(root, "lld", "LLD-0042-table.md", TABLE_HEADER)

            rendered = index.render("lld", index.collect("lld", root))

            self.assertIn("| 번호 | 제목 | 상태 | Issue |", rendered)
            self.assertIn("| [LLD-0042](LLD-0042-table.md) | 표 형식 문서 | Approved | #100 |",
                          rendered)


class MainTest(unittest.TestCase):
    def test_표를_출력하고_번호_중복을_경고한다(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(root, "lld", "LLD-0021-a.md", "# LLD-0021: 먼저 쓴 문서\n")
            write(root, "lld", "LLD-0021-b.md", "# LLD-0021: 나중에 쓴 문서\n")
            out, err = io.StringIO(), io.StringIO()

            with mock.patch.object(index, "DOCS_ROOT", root):
                with redirect_stdout(out), redirect_stderr(err):
                    code = index.main(["lld"])

            self.assertEqual(0, code)
            self.assertIn("LLD-0021-a.md", out.getvalue())
            self.assertIn("LLD-0021-b.md", out.getvalue())
            self.assertIn("[경고]", err.getvalue())

    def test_모르는_종류는_2를_반환한다(self):
        err = io.StringIO()

        with redirect_stderr(err):
            code = index.main(["erd"])

        self.assertEqual(2, code)
        self.assertIn("erd", err.getvalue())


class RepositoryDocumentTest(unittest.TestCase):
    """실제 문서가 헤더 형식을 지키는지 본다. 새 문서가 형식을 어기면 여기서 걸린다."""

    def test_모든_문서에서_제목과_상태를_읽는다(self):
        for kind in index.KINDS:
            entries = index.collect(kind, REPO_DOCS)
            self.assertTrue(entries, f"{kind} 문서를 찾지 못했습니다")
            for entry in entries:
                self.assertNotEqual("-", entry["title"], entry["file"])
                self.assertNotEqual("-", entry["status"], entry["file"])


if __name__ == "__main__":
    unittest.main()
