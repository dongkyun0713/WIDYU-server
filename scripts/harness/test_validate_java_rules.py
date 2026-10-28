"""validate-java-rules.sh 규칙 2(Service·Facade의 DTO 직접 생성) 회귀 검사.

Run with: python3 -m unittest discover -s scripts/harness -p 'test_*.py'.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parent / "validate-java-rules.sh"
SERVICE = "backend/widyu-api/src/main/java/com/widyu/home/application/HomeService.java"


class DtoCreationRuleTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        shutil.copy(SCRIPT, self.root / "validate-java-rules.sh")
        self.git("init", "-q")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Harness test")
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")

    def git(self, *args):
        subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)

    def write(self, path, *lines):
        dest = self.root / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text("class X {\n" + "".join(f"    {line}\n" for line in lines) + "}\n")

    def check(self, path):
        result = subprocess.run(["bash", "validate-java-rules.sh", path], cwd=self.root,
                                capture_output=True, text=True)
        return result.returncode, result.stdout

    def test_nested_dto_creation_in_service_is_blocked(self):
        self.write(SERVICE, "Object a() { return new GuardianHomeCardsResponse.MedicineInfo(1, null); }")
        code, out = self.check(SERVICE)
        self.assertEqual(code, 1, out)
        self.assertIn("[규칙2]", out)
        self.assertIn("GuardianHomeCardsResponse.MedicineInfo(", out)

    def test_top_level_and_digit_names_stay_blocked(self):
        for line in ("Object a() { return new HomeResponse(1); }",
                     "Object a() { return new HomeV2Response(1); }",
                     "Object a() { return new Outer2Dto.Inner3(1); }"):
            with self.subTest(line=line):
                self.write(SERVICE, line)
                code, out = self.check(SERVICE)
                self.assertEqual(code, 1, out)

    def test_factory_calls_are_allowed(self):
        self.write(SERVICE,
                   "Object a() { return GuardianHomeCardsResponse.from(1); }",
                   "Object b() { return GuardianHomeCardsResponse.MedicineInfo.of(1, null); }")
        code, out = self.check(SERVICE)
        self.assertEqual(code, 0, out)

    def test_nested_creation_outside_service_or_facade_is_allowed(self):
        path = "backend/widyu-api/src/main/java/com/widyu/home/dto/response/GuardianHomeCardsResponse.java"
        self.write(path, "static Object of() { return new GuardianHomeCardsResponse.MedicineInfo(1, null); }")
        code, out = self.check(path)
        self.assertEqual(code, 0, out)

    def test_only_added_nested_creation_is_blocked(self):
        legacy = "Object a() { return new SeniorGoalHomeResponse.StepsInfo(0, 0); }"
        self.write(SERVICE, legacy)
        self.git("add", ".")
        self.git("commit", "-qm", "legacy")

        self.write(SERVICE, legacy, "int unrelated() { return 1; }")
        code, out = self.check(SERVICE)
        self.assertEqual(code, 0, out)

        self.write(SERVICE, legacy, "Object b() { return new GuardianGoalHomeResponse.StepsInfo(0, 0); }")
        code, out = self.check(SERVICE)
        self.assertEqual(code, 1, out)
        self.assertIn("GuardianGoalHomeResponse.StepsInfo(", out)
        self.assertNotIn("SeniorGoalHomeResponse.StepsInfo(", out)


if __name__ == "__main__":
    unittest.main()
