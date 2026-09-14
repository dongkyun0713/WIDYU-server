"""Exercise CI Redis setup with fake package tools; never start a Redis service."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class PrepareRedisTest(unittest.TestCase):
    def run_setup(self, failure=""):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            binaries = temporary / "fakes"
            binaries.mkdir()
            log = temporary / "commands"
            github_path = temporary / "github-path"
            scripts = {
                "sudo": 'exec "$@"\n',
                "apt-get": 'echo "apt-get $*" >> "$FAKE_LOG"\n'
                           'if [[ "$1" == "$FAKE_FAILURE" ]]; then exit 7; fi\n'
                           'if [[ "$1" == download ]]; then touch redis-server.deb redis-tools.deb; fi\n',
                "dpkg-deb": 'echo "dpkg-deb $*" >> "$FAKE_LOG"\n'
                            'mkdir -p "$3/usr/bin"\n'
                            'printf \'#!/usr/bin/env bash\\necho "redis-server $*" >> "$FAKE_LOG"\\n'
                            '[[ "$*" == --version ]] || exit 9\\n'
                            '[[ "$FAKE_FAILURE" != version ]]\\n\' > "$3/usr/bin/redis-server"\n'
                            'chmod +x "$3/usr/bin/redis-server"\n',
            }
            for name, body in scripts.items():
                executable = binaries / name
                executable.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + body)
                executable.chmod(0o755)
            result = subprocess.run(["bash", str(ROOT / "scripts/ci/prepare-redis.sh")],
                                    env=os.environ | {"PATH": f"{binaries}:{os.environ['PATH']}",
                                                      "RUNNER_TEMP": str(temporary),
                                                      "GITHUB_PATH": str(github_path),
                                                      "FAKE_LOG": str(log), "FAKE_FAILURE": failure},
                                    capture_output=True, text=True)
            paths = ""
            if github_path.exists():
                paths = github_path.read_text()
            return result, log.read_text(), paths

    def test_extracts_packages_and_only_checks_version(self):
        result, commands, paths = self.run_setup()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("apt-get install -y --no-install-recommends redis-tools\n", commands)
        self.assertIn("apt-get download redis-server redis-tools\n", commands)
        self.assertEqual(commands.count("dpkg-deb --extract"), 2)
        self.assertIn("redis-server --version\n", commands)
        self.assertEqual(len(paths.splitlines()), 1)
        self.assertTrue(paths.strip().endswith("/bin/usr/bin"))

    def test_package_or_version_failure_does_not_publish_path(self):
        for failure in ("update", "install", "download", "version"):
            with self.subTest(failure=failure):
                result, _, paths = self.run_setup(failure)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(paths, "")

    def test_all_current_api_test_workflows_prepare_redis_first(self):
        workflows = []
        for path in (ROOT / ".github/workflows").glob("*.yml"):
            source = path.read_text()
            if ":backend:widyu-api:test" in source:
                workflows.append(path.name)
                self.assertLess(source.index("bash scripts/ci/prepare-redis.sh"),
                                source.index(":backend:widyu-api:test"), path.name)
        self.assertEqual(sorted(workflows), ["ci.yml", "deploy-prod.yml"])


if __name__ == "__main__":
    unittest.main()
