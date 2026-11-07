"""Exercise the workflow's actual release scripts without GitHub credentials."""

import copy
from contextlib import contextmanager, redirect_stdout
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


WORKFLOW = Path(__file__).resolve().parents[1] / "workflows" / "build-tv.yml"
COMMIT = "a" * 40
REPO = "example/BoostBuddy-TV"


def release_script(step_id):
    """Read a marked inline Python step so tests run the production code."""
    workflow = WORKFLOW.read_text()
    marker = f"        id: {step_id}\n"
    start = workflow.index(marker) + len(marker)
    block = workflow[start:].split("\n      - name:", 1)[0]
    run = block.split("        run: |\n", 1)[1]
    lines = []
    for line in run.splitlines():
        if line and not line.startswith("          "):
            break
        lines.append(line[10:])
    script = "\n".join(lines)
    if not script.startswith("python3 <<'PY'\n") or not script.rstrip().endswith("\nPY"):
        raise AssertionError(f"Expected an inline Python script in {step_id}")
    return compile(script.split("\n", 1)[1].rsplit("\nPY", 1)[0], str(WORKFLOW) + ":" + step_id, "exec")


@contextmanager
def working_directory(path):
    previous = Path.cwd()
    os.chdir(path)
    try:
        yield
    finally:
        os.chdir(previous)


class PrereleaseWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.env = {
            "GITHUB_SHA": COMMIT,
            "GITHUB_REPOSITORY": REPO,
            "GITHUB_SERVER_URL": "https://github.com",
            "GITHUB_RUN_ID": "12345",
            "GITHUB_RUN_NUMBER": "7",
            "GITHUB_RUN_ATTEMPT": "1",
            "BUILD_ATTEMPT": "1",
            "GITHUB_REF": "refs/heads/develop",
            "GITHUB_EVENT_NAME": "push",
            "GITHUB_OUTPUT": str(self.directory / "output"),
            "GITHUB_STEP_SUMMARY": str(self.directory / "summary"),
        }
        self.apk_name = f"BoostBuddy-TV-1.16.0-tv.2-c90-{COMMIT[:12]}-debug.apk"
        self.dist = self.directory / "dist"
        self.dist.mkdir()
        apk = b"synthetic APK bytes; no account or network access"
        checksum = hashlib.sha256(apk).hexdigest()
        (self.dist / self.apk_name).write_bytes(apk)
        (self.dist / "SHA256SUMS").write_text(f"{checksum}  {self.apk_name}\n")
        (self.dist / "INSTALL.txt").write_text("Synthetic installation notes\n")
        self.info = {
            "applicationId": "ru.slartus.boostbuddy.tvfork",
            "versionName": "1.16.0-tv.2",
            "versionCode": 90,
            "builtCommit": COMMIT,
            "sourceCommit": COMMIT,
            "runId": 12345,
            "runNumber": 7,
            "runAttempt": 1,
            "ref": self.env["GITHUB_REF"],
            "event": "push",
            "workflowRun": f"https://github.com/{REPO}/actions/runs/12345",
            "apk": self.apk_name,
            "sha256": checksum,
        }
        self.write_info()
        self.calls = []

    def write_info(self):
        (self.dist / "build-info.json").write_text(json.dumps(self.info) + "\n")

    def execute(self, step_id):
        with working_directory(self.directory), patch.dict(os.environ, self.env, clear=True), redirect_stdout(io.StringIO()):
            exec(release_script(step_id), {"__name__": "__main__"})

    def prepare(self):
        self.execute("prepare_release")
        return json.loads((self.directory / "release.json").read_text())

    def publisher(self, *, corrupt=None, failure=None, wrong_ref=False, wrong_published=False):
        release = self.prepare()
        assets = [dict(asset, state="uploaded") for asset in release["assets"]]
        draft = {"id": 42, "draft": True, "prerelease": True, "tag_name": release["tag"], "assets": assets}
        if corrupt:
            corrupt(draft)
        edited = False

        def fake_gh(command, **kwargs):
            nonlocal edited
            self.assertEqual(command[0], "gh")
            self.calls.append(command)
            if failure and failure(command):
                return subprocess.CompletedProcess(command, 1, "", "Synthetic GitHub rejection")
            if command[1:4] == ["api", "--method", "POST"]:
                self.assertIn(f"sha={COMMIT}", command)
                self.assertIn(f"ref=refs/tags/{release['tag']}", command)
                value = {"object": {"type": "commit", "sha": "b" * 40 if wrong_ref else COMMIT}}
            elif command[1:3] == ["release", "create"]:
                self.assertIn("--draft", command)
                self.assertIn("--verify-tag", command)
                self.assertIn("--prerelease", command)
                self.assertIn("--latest=false", command)
                self.assertNotIn("--clobber", command)
                self.assertEqual(set(command[4:8]), {asset["path"] for asset in release["assets"]})
                return subprocess.CompletedProcess(command, 0, "created draft\n", "")
            elif command[1:3] == ["release", "view"]:
                value = {"databaseId": 42}
            elif command[1:3] == ["release", "edit"]:
                self.assertIn("--draft=false", command)
                self.assertIn("--prerelease", command)
                self.assertIn("--latest=false", command)
                edited = True
                return subprocess.CompletedProcess(command, 0, "published\n", "")
            elif command[1:] == ["api", f"repos/{REPO}/releases/42"]:
                value = copy.deepcopy(draft)
                if edited:
                    value.update(draft=wrong_published, html_url=f"https://github.com/{REPO}/releases/tag/{release['tag']}")
            else:
                self.fail(f"Unexpected GitHub operation: {command}")
            return subprocess.CompletedProcess(command, 0, json.dumps(value), "")

        with patch("subprocess.run", side_effect=fake_gh):
            self.execute("publish_release")

    def assert_not_published(self):
        self.assertFalse(any(command[1:3] == ["release", "edit"] for command in self.calls))
        self.assertFalse((self.directory / "summary").exists())

    def test_prepares_exact_assets_and_preserves_build_attempt_on_publisher_rerun(self):
        self.env["GITHUB_RUN_ATTEMPT"] = "2"
        release = self.prepare()
        self.assertEqual(release["tag"], "ci-12345-2")
        self.assertEqual(release["commit"], COMMIT)
        self.assertEqual(len(release["assets"]), 4)
        self.assertEqual(json.loads((self.dist / "build-info.json").read_text())["runAttempt"], 1)
        self.assertIn("Build attempt: **1**; publish attempt: **2**", (self.directory / "release-notes.md").read_text())

    def test_rejects_artifact_from_another_commit_run_ref_or_build_attempt(self):
        original = dict(self.info)
        for key, value in [("builtCommit", "b" * 40), ("sourceCommit", "b" * 40), ("runId", 77),
                           ("runAttempt", 2), ("ref", "refs/heads/other"), ("event", "pull_request")]:
            with self.subTest(key=key):
                self.info = dict(original, **{key: value})
                self.write_info()
                with self.assertRaisesRegex(SystemExit, "provenance"):
                    self.prepare()

    def test_version_filename_sanitization_matches_packaging(self):
        self.info["versionName"] = "1.16.0-tv.2+ci"
        renamed = self.apk_name.replace("1.16.0-tv.2", "1.16.0-tv.2_ci")
        (self.dist / self.apk_name).rename(self.dist / renamed)
        self.info["apk"] = renamed
        self.write_info()
        (self.dist / "SHA256SUMS").write_text(f"{self.info['sha256']}  {renamed}\n")
        release = self.prepare()
        self.assertIn("1.16.0-tv.2+ci", release["title"])
        self.assertEqual(release["assets"][0]["name"], renamed)

    def test_rejects_changed_apk_and_checksum_file(self):
        original = (self.dist / self.apk_name).read_bytes()
        (self.dist / self.apk_name).write_bytes(b"modified after build")
        with self.assertRaisesRegex(SystemExit, "checksum"):
            self.prepare()
        (self.dist / self.apk_name).write_bytes(original)
        (self.dist / "SHA256SUMS").write_text("incorrect checksum file\n")
        with self.assertRaisesRegex(SystemExit, "SHA256SUMS"):
            self.prepare()

    def test_rejects_unexpected_files_paths_and_symlinks(self):
        self.info["apk"] = "../outside.apk"
        self.write_info()
        with self.assertRaisesRegex(SystemExit, "filename"):
            self.prepare()
        self.info["apk"] = self.apk_name
        self.write_info()
        (self.dist / "extra.sh").write_text("unexpected")
        with self.assertRaisesRegex(SystemExit, "exactly"):
            self.prepare()
        (self.dist / "extra.sh").unlink()
        (self.dist / "INSTALL.txt").unlink()
        (self.directory / "outside.txt").write_text("outside")
        (self.dist / "INSTALL.txt").symlink_to(self.directory / "outside.txt")
        with self.assertRaisesRegex(SystemExit, "Invalid release asset"):
            self.prepare()

    def test_publishes_verified_assets_at_exact_commit(self):
        self.publisher()
        self.assertIn("ci-12345-1", (self.directory / "summary").read_text())
        self.assertIn("url=https://github.com/", (self.directory / "output").read_text())

    def test_existing_tag_rejection_does_not_create_or_overwrite_release(self):
        with self.assertRaisesRegex(SystemExit, "publication failed"):
            self.publisher(failure=lambda command: command[1:4] == ["api", "--method", "POST"])
        self.assertEqual(len(self.calls), 1)
        self.assert_not_published()

    def test_wrong_tag_target_prevents_release_creation(self):
        with self.assertRaisesRegex(SystemExit, "exact built commit"):
            self.publisher(wrong_ref=True)
        self.assertEqual(len(self.calls), 1)
        self.assert_not_published()

    def test_failed_asset_upload_leaves_draft_unpublished(self):
        with self.assertRaisesRegex(SystemExit, "publication failed"):
            self.publisher(failure=lambda command: command[1:3] == ["release", "create"])
        self.assert_not_published()

    def test_incomplete_or_corrupt_remote_assets_are_not_published(self):
        changes = [
            lambda draft: draft["assets"].pop(),
            lambda draft: draft["assets"][0].update(state="starter"),
            lambda draft: draft["assets"][0].update(size=1),
            lambda draft: draft["assets"][0].update(digest="sha256:" + "0" * 64),
            lambda draft: draft.update(prerelease=False),
        ]
        for change in changes:
            with self.subTest(change=change):
                self.calls.clear()
                with self.assertRaises(SystemExit):
                    self.publisher(corrupt=change)
                self.assert_not_published()

    def test_no_success_summary_when_publication_is_not_confirmed(self):
        with self.assertRaisesRegex(SystemExit, "did not publish"):
            self.publisher(wrong_published=True)
        self.assertFalse((self.directory / "summary").exists())


if __name__ == "__main__":
    unittest.main()
