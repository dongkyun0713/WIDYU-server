#!/usr/bin/env python3
"""Shared local/CI verification. Requires Python 3.9+, Git, Bash."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def changed_paths(root, base="HEAD"):
    # Invalid refs must never look like an empty diff. No-renames keeps both paths.
    base = git(root, "rev-parse", "--verify", base + "^{commit}").decode().strip()
    paths = git(root, "diff", "--no-renames", "--name-only", "-z", base, "--")
    paths += git(root, "ls-files", "--others", "--exclude-standard", "-z")
    return base, sorted({os.fsdecode(p) for p in paths.split(b"\0") if p})


def affected(paths):
    domain = any(p.startswith("backend/widyu-domain/") and not p.endswith(".md") for p in paths)
    api = any(p.startswith("backend/widyu-api/") and not p.endswith(".md") for p in paths)
    shared = any(p in {"build.gradle", "settings.gradle", "gradle.properties",
                              "gradlew", "gradlew.bat", "backend/build.gradle"}
                 or p.startswith(("gradle/", "buildSrc/")) for p in paths)
    modules = []
    if domain or shared:
        modules.append("domain")
    if api or domain or shared:
        modules.append("api")
    return modules, any(p.startswith("admin/") and not p.endswith(".md") for p in paths)


def run(root, command, env=None):
    print("[HARNESS] " + " ".join(command), flush=True)
    subprocess.run(command, cwd=root, env=env, check=True)


def check_rules(root, paths, base):
    env = dict(os.environ, HARNESS_DIFF_BASE=base)
    for path in paths:
        if (path.startswith(("backend/widyu-api/src/main/java/",
                             "backend/widyu-domain/src/main/java/"))
                and path.endswith(".java") and (root / path).is_file()):
            run(root, ["bash", str(root / "scripts/harness/validate-java-rules.sh"),
                       str(root / path)], env)


def test_modules(root, modules):
    if not modules:
        print("[HARNESS] backend 변경 없음: 모듈 테스트 생략")
        return
    run(root, [str(root / "gradlew"),
               *[f":backend:widyu-{m}:test" for m in modules], "--console=plain"])


def main(argv=None, root=ROOT):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="HEAD")
    parser.add_argument("--static-only", action="store_true")
    parser.add_argument("--tests-only", action="store_true")
    parser.add_argument("--module", choices=("api", "domain", "all"))
    args = parser.parse_args(argv)
    if args.static_only and args.tests_only:
        parser.error("--static-only와 --tests-only는 함께 사용할 수 없습니다")
    if args.module and not args.tests_only:
        parser.error("--module은 --tests-only와 함께 사용합니다")
    try:
        base, paths = changed_paths(root, args.base)
        modules, admin = affected(paths)
        if args.module == "api":
            modules = ["api"]
        elif args.module in ("domain", "all"):
            modules = ["domain", "api"]
        if args.tests_only:
            test_modules(root, modules)
            return 0
        print(f"[HARNESS] base={base[:12]}, 변경 파일 {len(paths)}개", flush=True)
        check_rules(root, paths, base)
        if args.static_only:
            print("[HARNESS] 정적 검사 완료 (컴파일·테스트·의미 검수 미포함)")
            return 0
        if modules:
            run(root, [str(root / "gradlew"), "compileJava", "--console=plain"])
        test_modules(root, modules)
        if admin:
            run(root / "admin", ["npm", "run", "lint"])
            run(root / "admin", ["npm", "run", "build"])
        if not modules and not admin:
            print("[HARNESS] 애플리케이션 변경 없음: Gradle/npm 생략")
        return 0
    except subprocess.CalledProcessError as error:
        print(f"[HARNESS] 검증 실패: 종료 코드 {error.returncode}", file=sys.stderr)
        return error.returncode or 1
    except OSError as error:
        print(f"[HARNESS] 실행 불가: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
