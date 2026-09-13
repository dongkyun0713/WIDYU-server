"""Release checks must detect committed violations without a working-tree diff."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ReleaseStaticTest(unittest.TestCase):
    def test_clean_checkout_is_scanned(self):
        for source, expected in ((None, 1), ("class Example {}\n", 0),
                                 ("class Example { int n = true ? 1 : 2; }\n", 1)):
            with self.subTest(source=source), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for script in ("scripts/docker/validate-release.sh", "scripts/harness/validate-java-rules.sh"):
                    target = root / script
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(ROOT / script, target)
                if source is not None:
                    java = root / "backend/widyu-api/src/main/java/Example.java"
                    java.parent.mkdir(parents=True)
                    java.write_text(source)
                def git(*args):
                    return subprocess.run(["git", *args], cwd=root, check=True,
                                          capture_output=True, text=True).stdout
                git("init")
                git("add", ".")
                git("-c", "user.name=Test", "-c", "user.email=test@example.com",
                    "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null",
                    "commit", "-m", "release")
                self.assertEqual(git("status", "--porcelain"), "")
                result = subprocess.run(["bash", "scripts/docker/validate-release.sh"],
                                        cwd=root, capture_output=True, text=True)
                self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
