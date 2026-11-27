import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


CHECK = Path(__file__).resolve().parents[2] / ".github" / "scripts" / "android-promote.py"


class CandidatePromotionTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.dist = self.root / "dist"
        self.dist.mkdir()
        repo, sha, version = "team/PebblePile", "a" * 40, "0.1.0"
        self.apk = self.dist / f"PebblePile-{version}.apk"
        self.apk.write_bytes(b"accepted candidate")
        digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.info = dict(commit=sha, versionName=version, applicationId="com.pebblepile.android",
                         apk=self.apk.name, sha256=digest, workflowRunId="10", workflowRunAttempt="2")
        self.run_info = dict(id=10, run_attempt=2, head_sha=sha, repository={"full_name": repo},
                             head_repository={"full_name": repo}, event="workflow_dispatch",
                             path=".github/workflows/android-release.yml", conclusion="success")
        self.env = dict(os.environ, GITHUB_REPOSITORY=repo, SOURCE_RUN_ID="10",
                        SOURCE_ARTIFACT=f"PebblePile-android-{version}-{sha}-2",
                        EXPECTED_SHA256=digest, RUN_JSON=str(self.root / "run.json"),
                        GITHUB_OUTPUT=str(self.root / "outputs"))
        (self.dist / "SHA256SUMS.txt").write_text(f"{digest}  {self.apk.name}\n")
        (self.dist / "release-notes.md").write_text("Test notes")

    def verify(self):
        (self.dist / "build-info.json").write_text(json.dumps(self.info))
        Path(self.env["RUN_JSON"]).write_text(json.dumps(self.run_info))
        return subprocess.run([sys.executable, str(CHECK)], cwd=self.root, env=self.env,
                              capture_output=True, text=True)

    def test_accepted_candidate(self):
        result = self.verify()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"sha256={self.info['sha256']}", Path(self.env["GITHUB_OUTPUT"]).read_text())

    def test_changed_apk(self):
        self.apk.write_bytes(b"rebuilt candidate")
        self.assertNotEqual(self.verify().returncode, 0)

    def test_unrelated_workflow_or_attempt(self):
        for key, value in [("path", ".github/workflows/android-ci.yml"), ("run_attempt", 3),
                           ("head_sha", "b" * 40), ("head_repository", {"full_name": "fork/PebblePile"})]:
            with self.subTest(key=key):
                previous = self.run_info[key]
                self.run_info[key] = value
                self.assertNotEqual(self.verify().returncode, 0)
                self.run_info[key] = previous

    def test_expected_digest_is_required_and_matches_the_bytes(self):
        for digest in ["", "b" * 64]:
            with self.subTest(digest=digest):
                self.env["EXPECTED_SHA256"] = digest
                self.assertNotEqual(self.verify().returncode, 0)
