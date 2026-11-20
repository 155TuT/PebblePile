"""Check that the release APK is the signed candidate recorded by the tester."""
import hashlib
import json
import os
import re
from pathlib import Path


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def main():
    dist = Path("dist")
    run = json.loads(Path(os.environ["RUN_JSON"]).read_text(encoding="utf-8"))
    info = json.loads((dist / "build-info.json").read_text(encoding="utf-8"))
    repo = os.environ["GITHUB_REPOSITORY"]
    require(run["path"] == ".github/workflows/android-release.yml"
            and run["event"] == "workflow_dispatch" and run["conclusion"] == "success"
            and run["repository"]["full_name"] == repo and run["head_repository"]["full_name"] == repo,
            "Choose a successful Android release workflow run from the publishing repository.")
    commit = info["commit"]
    require(re.fullmatch(r"[0-9a-f]{40}", commit) and commit == run["head_sha"],
            "Candidate commit differs from the workflow run.")
    require(str(run["id"]) == info["workflowRunId"] == os.environ["SOURCE_RUN_ID"]
            and str(run["run_attempt"]) == info["workflowRunAttempt"], "Choose the verified run attempt.")
    version = info["versionName"]
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-(?:alpha|beta|rc)\.[0-9]+)?", version),
            "Choose an alpha, beta, rc or final version.")
    apk = f"PebblePile-{version}.apk"
    require(info["applicationId"] == "com.pebblepile.android" and info["apk"] == apk
            and os.environ["SOURCE_ARTIFACT"] == f"PebblePile-android-{version}-{commit}-{run['run_attempt']}",
            "Candidate package or artifact name differs from the run.")
    with (dist / apk).open("rb") as handle:
        digest = hashlib.file_digest(handle, "sha256").hexdigest()
    require(digest == info["sha256"] == os.environ["EXPECTED_SHA256"].lower()
            and (dist / "SHA256SUMS.txt").read_text().splitlines() == [f"{digest}  {apk}"],
            "APK bytes, checksum file, build metadata and acceptance SHA-256 must match.")
    require((dist / "release-notes.md").is_file(), "Candidate release notes are missing.")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        print(f"version_name={version}\ncommit={commit}\nsha256={digest}", file=output)


if __name__ == "__main__":
    main()
