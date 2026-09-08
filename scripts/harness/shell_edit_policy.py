"""Catch direct shell edits; arbitrary program side effects remain outside this policy."""
from pathlib import Path
import re
import shlex
import subprocess
import tempfile


EDIT_MESSAGE = "셸 직접 파일 수정은 차단합니다. 대상 worktree에서 apply_patch를 사용해 브랜치 검사를 받으세요."
EDITORS = {"tee", "cp", "mv", "rm", "touch", "truncate", "install", "ln", "patch", "dd"}
INTERPRETERS = {"python", "python3", "node", "ruby", "perl", "bash", "sh", "zsh"}


def scratch_output(value, cwd, root, ack):
    """Only literal temporary outputs or the exact session ack may bypass patching."""
    if value == "/dev/null":
        return True
    if any(c in value for c in "$`*?{}"):
        return False
    target = (cwd / value).resolve()
    if ack is not None and target == ack and not ack.is_symlink():
        return True
    if target.is_relative_to(root):
        return False
    temporary_roots = (Path(tempfile.gettempdir()).resolve(), Path("/tmp").resolve())
    if not any(target.is_relative_to(directory) for directory in temporary_roots):
        return False
    parent = target.parent
    while not parent.exists():
        parent = parent.parent
    result = subprocess.run(["git", "-C", str(parent), "rev-parse", "--show-toplevel"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return result.returncode != 0


def commands(text):
    """Tokenize statements and retain quoted arguments; heredoc bodies are data."""
    kept = []
    pending = []
    index = 0
    while index < len(text):
        char = text[index]
        if char == "#" and (index == 0 or text[index - 1] in " \t\n;&|"):
            end = text.find("\n", index)
            index = len(text) if end < 0 else end
            continue
        if char == "\\" and index + 1 < len(text):
            kept.append(text[index:index + 2])
            index += 2
            continue
        if char in "'\"":
            end = index + 1
            while end < len(text):
                if char == '"' and text[end] == "\\":
                    end += 2
                    continue
                if text[end] == char:
                    break
                end += 1
            if end >= len(text):
                raise ValueError("Unclosed quote")
            kept.append(text[index:end + 1])
            index = end + 1
            continue
        if text.startswith("<<<", index):
            kept.append("<<<")
            index += 3
            continue
        if text.startswith("<<", index):
            match = re.match(r"<<-?\s*(['\"]?)([A-Za-z_][A-Za-z_0-9]*)\1", text[index:])
            if not match:
                raise ValueError("Unsupported heredoc delimiter")
            pending.append(match.group(2))
            kept.append(match.group())
            index += len(match.group())
            continue
        kept.append(char)
        index += 1
        if char == "\n":
            while pending:
                delimiter = pending.pop(0)
                while True:
                    end = text.find("\n", index)
                    if end < 0:
                        end = len(text)
                    line = text[index:end]
                    index = min(end + 1, len(text))
                    if line.strip() == delimiter:
                        break
                    if end == len(text):
                        raise ValueError("Unclosed heredoc")
    if pending:
        raise ValueError("Unclosed heredoc")
    lexer = shlex.shlex("".join(kept), posix=False, punctuation_chars="\n;&|()<>")
    lexer.whitespace = " \t\r"
    lexer.whitespace_split = True
    current = []
    for token in lexer:
        if token and all(c in "\n;&|()" for c in token):
            if current:
                yield current
                current = []
        else:
            current.append(token)
    if current:
        yield current


def check(text, cwd, root, ack):
    try:
        statements = list(commands(text))
    except ValueError:
        return "셸 구문을 확인할 수 없습니다. 명령을 나눠 실행하거나 apply_patch를 사용하세요."
    for tokens in statements:
        # Keep raw tokens for redirections so quoted '>' remains a normal argument.
        raw = tokens
        try:
            tokens = [shlex.split(token)[0] for token in raw]
        except (ValueError, IndexError):
            return "셸 인자를 확인할 수 없습니다. 명령을 나눠 실행하세요."
        while tokens and (re.match(r"^[A-Za-z_][A-Za-z_0-9]*=", tokens[0]) or tokens[0] in ("command", "exec", "env")):
            tokens = tokens[1:]
        if not tokens:
            continue
        name = Path(tokens[0]).name
        if name == "cd":
            if len(tokens) != 2 or any(c in tokens[1] for c in "$`~"):
                return "cd는 명시적 경로로 별도 실행하세요."
            cwd = (cwd / tokens[1]).resolve()
            continue
        for index, token in enumerate(raw):
            if token in (">", ">>", ">|", "&>", "&>>"):
                if index + 1 >= len(raw) or not scratch_output(shlex.split(raw[index + 1])[0], cwd, root, ack):
                    return EDIT_MESSAGE
            elif token == ">&":
                # File-descriptor duplication (2>&1) does not name a file.
                if index + 1 >= len(raw) or not raw[index + 1].isdigit():
                    return EDIT_MESSAGE
        if name in EDITORS:
            return EDIT_MESSAGE
        if name == "sed" and any(re.match(r"^(-[^-]*i|--in-place)", t) for t in tokens[1:]):
            return EDIT_MESSAGE
        if name == "git" and "apply" in tokens[1:]:
            return EDIT_MESSAGE
        if name in INTERPRETERS or re.fullmatch(r"python3\.\d+", name):
            # Inline code has no reliable list of write destinations. Keep it out of
            # this escape hatch; checked-in scripts and module-based tests still work.
            if any(t in ("-c", "-e", "--eval", "-", "<<", "<<-") or
                   t.startswith("--eval=") or re.match(r"^-[^-]*[ce]", t) or
                   (name == "perl" and t.startswith("-i")) for t in tokens[1:]):
                return "인라인 스크립트는 쓰기 대상을 검증할 수 없습니다. 파일 수정은 apply_patch로, 반복 검사는 저장소 스크립트로 실행하세요."
    return ""
