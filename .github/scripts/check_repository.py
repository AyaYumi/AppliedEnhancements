"""Validate publishable repository files and current documentation."""
from pathlib import Path
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
properties = dict(re.findall(r"^([a-z_]+)=(.*)$", (ROOT / "gradle.properties").read_text(encoding="utf-8"), re.M))
allowed_docs = {"API_INTEGRATION.md", "API_INTEGRATION_ZH.md",
                "BATCH_EXECUTION_API.md", "EXACT_CRAFTING_API.md"}
allowed_markdown = {"README.md", "README_ZH.md", "CONFIGURATION.md"} | {
    f"docs/{name}" for name in allowed_docs
}
paths = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
                                cwd=ROOT).decode("utf-8").split("\0")
failures = []
for source in ROOT.glob("*.md"):
    if source.name not in allowed_markdown:
        failures.append(f"{source.name}: not project or public API documentation")
for source in (ROOT / "docs").rglob("*"):
    if source.is_file() and (source.parent != ROOT / "docs" or source.name not in allowed_docs):
        failures.append(f"{source.relative_to(ROOT).as_posix()}: not public API documentation (including ignored files)")
for relative in filter(None, paths):
    source = ROOT / relative
    if not source.is_file():
        continue  # A tracked deletion is valid before committing.
    lower = relative.lower()
    parts = Path(lower).parts
    if (any(p in {"build", ".gradle", "logs", "run", "runs", "screenshots", "output", "test-results",
                  ".idea", ".vscode", ".claude", ".worktrees", "__pycache__", "codex-backups", "scratch"} for p in parts)
            or source.suffix.lower() in {".log", ".tmp", ".bak", ".old", ".orig", ".rej", ".pyc", ".class",
                                         ".zip", ".hprof", ".jfr", ".sparkprofile", ".bbmodel", ".psd", ".xcf", ".blend"}
            or lower.endswith((".log.gz", "_javap.txt", "~"))
            or (source.suffix.lower() == ".jar" and relative != "gradle/wrapper/gradle-wrapper.jar")
            or lower.startswith(("tools/client/", "tools/pack/", "tools/ui/"))
            or source.name.lower() in {"desktop.ini", "thumbs.db", ".ds_store"}):
        failures.append(f"{relative}: temporary/generated resource")
    if source.suffix.lower() == ".md" and relative not in allowed_markdown:
        failures.append(f"{relative}: not required project or public API documentation")
    if (relative.startswith(("src/test/", "src/integrationTest/", "qa/", "tools/qa/"))
            or any(part in {"fixtures", "harness", "qa-src", "qa-resources"} for part in parts)):
        failures.append(f"{relative}: removed development fixture or test source")
    if relative.startswith("src/main/java/") and source.suffix == ".java":
        content = source.read_text(encoding="utf-8")
        if re.search(r"\bSystem\.(?:out|err)\b|\.(?:printStackTrace|dumpStack)\s*\(", content):
            failures.append(f"{relative}: direct console/stack trace output in production code")
        if re.search(r"\b(?:LOGGER|logger|LOG|log)\.(?:trace|debug|info)\s*\(", content):
            failures.append(f"{relative}: routine/development logging in production code")
        if (relative != "src/main/java/com/appliedenhancements/runtime/AelisPlanningLog.java"
                and re.search(r"\b(?:LOGGER|logger|LOG|log)\.(?:warn|error)\s*\(", content)):
            failures.append(f"{relative}: fault logging bypasses the shared rate limiter")
    if source.suffix.lower() in {".json", ".mcmeta"}:
        try:
            json.loads(source.read_text(encoding="utf-8"))
        except (ValueError, UnicodeError) as error:
            failures.append(f"{relative}: {error}")
    if source.suffix.lower() == ".md":
        content = source.read_text(encoding="utf-8")
        for target in re.findall(r"\]\(([^)]+)\)", content):
            target = target.strip("<>").split("#", 1)[0]
            if target and not re.match(r"[a-zA-Z][a-zA-Z0-9+.-]*:", target) and not (source.parent / target).exists():
                failures.append(f"{relative}: missing link {target}")
        if re.search(r"(?:[CD]:[/\\](?:Users|Project)|/home/[^ /]+/)", content):
            failures.append(f"{relative}: machine-specific path")
        for target in re.findall(r'<(?:img|a)\b[^>]*(?:src|href)="([^"]+)"', content):
            if not re.match(r"[a-zA-Z][a-zA-Z0-9+.-]*:", target) and not (source.parent / target).exists():
                failures.append(f"{relative}: missing HTML link {target}")
        if relative.startswith("docs/") or (source.name.startswith("README") and source.parent == ROOT):
            if properties["mod_version"] not in content[:1800]:
                failures.append(f"{relative}: current mod version missing from introduction")
            if properties["minecraft_version"] == "1.20.1" and re.search(r"\b(?:1\.21\.1|19\.2\.1[78]|net\.neoforged)\b", content):
                failures.append(f"{relative}: NeoForge version/API text in Forge documentation")
            if properties["minecraft_version"] not in content[:2200]:
                failures.append(f"{relative}: current Minecraft version missing from introduction")
if failures:
    raise SystemExit("\n".join(failures))
print(f"Repository files, resource JSON and documentation validated for {properties['mod_version']}")
