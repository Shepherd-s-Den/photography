import contextlib
import io
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from check_guidance import LIMIT, SKILL, inspect, inventory


class GuidanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.skill = self.root / SKILL
        self.skill.mkdir(parents=True)
        for name in ("AGENTS.md", "DESIGN-PRINCIPLES.md", "CONTRIBUTING.md", ".agents/README.md"):
            (self.root / name).write_text("# Entrypoint\n")
        for name in ("SKILL.md", "executor.md", "reviewer.md", "principles.md",
                     "conventions.md", "context.md", "local.md",
                     "templates/change-spec.md", "templates/design-map.md", "templates/review.md"):
            path = self.skill / name
            path.parent.mkdir(exist_ok=True)
            path.write_text("# Guidance\n")

    def inspect(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return inspect(self.root)

    def test_should_count_physical_lines_including_blanks_and_new_guidance(self):
        (self.skill / "extra.md").write_text("# Extra\n\n")
        self.assertEqual(self.inspect()[0], 16)

    def test_should_reject_over_budget(self):
        (self.skill / "extra.md").write_text("\n" * LIMIT)
        with self.assertRaisesRegex(ValueError, "exceed"):
            self.inspect()

    def test_should_reject_missing_owner(self):
        (self.skill / "principles.md").unlink()
        with self.assertRaisesRegex(ValueError, "missing guidance"):
            self.inspect()

    def test_should_reject_wrapped_prose(self):
        (self.skill / "extra.md").write_text("first\ncontinued\n")
        with self.assertRaisesRegex(ValueError, "wrapping"):
            self.inspect()

    def test_should_count_domain_wrappers_templates_and_agent_entrypoints(self):
        owner = self.root / "domains/media/audiobookshelf"
        for name in ("AGENTS.md", "CONTRIBUTING.md", "DESIGN-PRINCIPLES.md",
                     "specs/templates/new.md", "frontend/UX-PATTERNS.md"):
            path = owner / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("# Owner\n")
        (self.root / "AGENTS.md").write_text("# Root\n")
        self.assertEqual(self.inspect()[0], 19)
        self.assertEqual(len(inventory(self.root)), 19)

    def test_should_exclude_local_facts_but_detect_shared_rule_change(self):
        initial = self.inspect()[1]
        (self.skill / "local.md").write_text("# Local\n")
        self.assertEqual(initial, self.inspect()[1])
        (self.skill / "principles.md").write_text("# Changed\n")
        self.assertNotEqual(initial, self.inspect()[1])

    def test_should_count_instruction_wrappers_regardless_of_filename(self):
        path = self.root / ".github/instructions/policy.md"
        path.parent.mkdir(parents=True)
        path.write_text("# Instructions\n")
        self.assertEqual(self.inspect()[0], 15)

    def test_should_reject_missing_entrypoint(self):
        (self.root / "AGENTS.md").unlink()
        with self.assertRaisesRegex(ValueError, "missing entrypoint"):
            self.inspect()

    def test_should_check_cli_peer_parity_and_drift(self):
        command = [sys.executable, str(Path(__file__).with_name("check_guidance.py")),
                   "--root", str(self.root), "--peer", str(self.root)]
        self.assertEqual(subprocess.run(command, capture_output=True).returncode, 0)
        with tempfile.TemporaryDirectory() as directory:
            peer = Path(directory)
            shutil.copytree(self.root, peer, dirs_exist_ok=True)
            (peer / SKILL / "principles.md").write_text("# Different\n")
            command[-1] = str(peer)
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("shared content differ", result.stderr)
            shutil.copyfile(self.skill / "principles.md", peer / SKILL / "principles.md")
            (peer / SKILL / "local.md").write_text("# Local\n\n")
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("totals", result.stderr)


if __name__ == "__main__":
    unittest.main()
