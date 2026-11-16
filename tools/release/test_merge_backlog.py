#!/usr/bin/env python3
"""Tests of tools/release/merge_backlog.py: python3 tools/release/test_merge_backlog.py"""
import pathlib
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import merge_backlog as mb  # noqa: E402


def row(i, summary="s", typ=None, parent="", status="To Do", ac="- one\n- two", **kw):
    typ = typ or ("Epic" if i.startswith("E") else "Story")
    return [i, typ, summary, kw.get("desc", "d"), ac, parent, summary if typ == "Epic" else "", "High", "3", "x", "c", status]


def text(*rows, crlf=True):
    return mb.serialize(list(rows), crlf)


EPICS = [row("E01"), row("E02")]


class MergeBacklog(unittest.TestCase):
    def ids(self, t):
        return [r[0] for r in mb.parse(t, "t")]

    def test_both_sides_append_multi_line_rows_without_interleaving(self):
        base = text(*EPICS, row("S001", parent="E01"))
        theirs = text(*EPICS, row("S001", parent="E01"), row("S002", parent="E02", ac="- a\n- b\n- c"))
        ours = text(*EPICS, row("S001", parent="E01"), row("E37", "Release 1"), row("S600", parent="E37", ac="- x\n- y\n- z"))
        merged, report = mb.merge(base, ours, theirs)
        self.assertEqual(self.ids(merged), ["E01", "E02", "S001", "S002", "E37", "S600"])
        rows = {r[0]: r for r in mb.parse(merged, "m")}
        self.assertEqual(rows["S002"][4], "- a\n- b\n- c")
        self.assertEqual(rows["S600"][4], "- x\n- y\n- z")
        self.assertEqual(report, [])

    def test_nothing_new_is_byte_identical_and_keeps_crlf(self):
        base = text(*EPICS, row("S001", parent="E01"))
        merged, _ = mb.merge(base, base, base)
        self.assertEqual(merged, base)
        self.assertIn('"\r\n', merged)
        lf = text(*EPICS, row("S001", parent="E01"), crlf=False)
        self.assertNotIn("\r\n", mb.merge(lf, lf, lf)[0])

    def test_main_only_change_and_release_only_change(self):
        base = text(*EPICS, row("S001", parent="E01"), row("S002", parent="E01"))
        theirs = text(*EPICS, row("S001", parent="E01", status="Done"), row("S002", parent="E01"))
        ours = text(*EPICS, row("S001", parent="E01"), row("S002", parent="E01", summary="renamed"))
        merged, _ = mb.merge(base, ours, theirs)
        rows = {r[0]: r for r in mb.parse(merged, "m")}
        self.assertEqual(rows["S001"][11], "Done")
        self.assertEqual(rows["S002"][2], "renamed")

    def test_same_row_different_fields_is_merged_field_by_field(self):
        base = text(*EPICS, row("S001", parent="E01"))
        theirs = text(*EPICS, row("S001", parent="E01", status="Done"))
        ours = text(*EPICS, row("S001", parent="E01", summary="clearer"))
        merged, report = mb.merge(base, ours, theirs)
        r = mb.parse(merged, "m")[2]
        self.assertEqual((r[2], r[11]), ("clearer", "Done"))
        self.assertTrue(any("merged" in x for x in report))

    def test_same_field_two_values_is_a_conflict_not_a_guess(self):
        base = text(*EPICS, row("S001", parent="E01"))
        theirs = text(*EPICS, row("S001", parent="E01", status="Done"))
        ours = text(*EPICS, row("S001", parent="E01", status="In Progress"))
        with self.assertRaises(mb.Refused) as c:
            mb.merge(base, ours, theirs)
        self.assertIn("S001", str(c.exception))
        self.assertIn("Status", str(c.exception))

    def test_story_id_collision_renumbers_the_release_row_into_the_block(self):
        base = text(*EPICS, row("S001", parent="E01"))
        theirs = text(*EPICS, row("S001", parent="E01"), row("S600", "main took S600", parent="E02"))
        ours = text(*EPICS, row("S001", parent="E01"), row("S600", "release-1 story", parent="E02"), row("S601", "second", parent="E02"))
        merged, report = mb.merge(base, ours, theirs)
        rows = {r[0]: r for r in mb.parse(merged, "m")}
        self.assertEqual(rows["S600"][2], "main took S600")          # main's id never moves
        self.assertEqual(rows["S601"][2], "second")
        self.assertEqual(rows["S602"][2], "release-1 story")         # the next free id of the block, after S601
        self.assertTrue(any(x.startswith("RENUMBERED S600 -> S602") for x in report), report)

    def test_epic_collision_renumbers_the_release_epic_and_its_children(self):
        base = text(*EPICS)
        theirs = text(*EPICS, row("E03", "main epic"), row("S010", parent="E03"))
        ours = text(*EPICS, row("E03", "Release 1"), row("S600", parent="E03"))
        merged, report = mb.merge(base, ours, theirs)
        rows = {r[0]: r for r in mb.parse(merged, "m")}
        self.assertEqual(rows["E03"][2], "main epic")
        self.assertEqual(rows["S010"][5], "E03")                    # main's child stays with main's epic
        self.assertEqual(rows["E04"][2], "Release 1")
        self.assertEqual(rows["S600"][5], "E04")                    # the release child follows its epic
        self.assertTrue(any("E03 -> E04" in x for x in report), report)

    def test_both_added_the_same_row_is_taken_once(self):
        base = text(*EPICS)
        both = text(*EPICS, row("S050", parent="E01"))
        self.assertEqual(self.ids(mb.merge(base, both, both)[0]), ["E01", "E02", "S050"])

    def test_removals(self):
        base = text(*EPICS, row("S001", parent="E01"), row("S002", parent="E01"))
        merged, _ = mb.merge(base, base, text(*EPICS, row("S002", parent="E01")))
        self.assertEqual(self.ids(merged), ["E01", "E02", "S002"])
        with self.assertRaises(mb.Refused):
            mb.merge(base, text(*EPICS, row("S001", parent="E01", status="Done"), row("S002", parent="E01")),
                     text(*EPICS, row("S002", parent="E01")))

    def test_a_corrupt_input_is_refused_with_the_offending_ids(self):
        good = text(*EPICS, row("S001", parent="E01"))
        corrupt = good + '"- Tests pass","E02","","Medium","3","x","identity","Done"\r\n'
        with self.assertRaises(mb.Refused) as c:
            mb.merge(good, corrupt, good)
        self.assertIn("Tests pass", str(c.exception))
        bad_status = text(*EPICS, row("S001", parent="E01", status="Blocked"))
        with self.assertRaises(mb.Refused):
            mb.merge(good, good, bad_status)

    def test_status_in_review_is_valid(self):
        t = text(*EPICS, row("S001", parent="E01", status="In Review"))
        self.assertEqual(mb.merge(t, t, t)[0], t)

    def test_cli_git_mode_and_conflict_leaves_the_file_untouched(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            git = lambda *a: subprocess.run(["git", "-C", d, "-c", "user.email=t@t", "-c", "user.name=t", *a], check=True, capture_output=True)  # noqa: E731
            (root / "docs/backlog").mkdir(parents=True)
            f = root / "docs/backlog/backlog.csv"
            base = text(*EPICS, row("S001", parent="E01"))
            f.write_bytes(base.encode())
            git("init", "-q", "-b", "main")
            git("add", "-A")
            git("commit", "-q", "-m", "base")
            git("checkout", "-q", "-b", "release-1")
            git("checkout", "-q", "main")
            f.write_bytes(text(*EPICS, row("S001", parent="E01"), row("S002", parent="E02")).encode())
            git("commit", "-qam", "main adds")
            git("checkout", "-q", "release-1")
            f.write_bytes(text(*EPICS, row("S001", parent="E01"), row("E37", "R1"), row("S600", parent="E37")).encode())
            git("commit", "-qam", "release adds")
            r = subprocess.run([sys.executable, str(HERE / "merge_backlog.py"), "--git", "main", "--root", d], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(self.ids(f.read_bytes().decode()), ["E01", "E02", "S001", "S002", "E37", "S600"])
            # now a conflict: both change S001's status differently
            git("commit", "-qam", "merged file")
            git("checkout", "-q", "main")
            f.write_bytes(text(*EPICS, row("S001", parent="E01", status="Done"), row("S002", parent="E02")).encode())
            git("commit", "-qam", "main marks done")
            git("checkout", "-q", "release-1")
            before = text(*EPICS, row("S001", parent="E01", status="In Progress"), row("S002", parent="E02"), row("E37", "R1"), row("S600", parent="E37"))
            f.write_bytes(before.encode())
            r = subprocess.run([sys.executable, str(HERE / "merge_backlog.py"), "--git", "main", "--root", d], capture_output=True, text=True)
            self.assertEqual(r.returncode, 2)
            self.assertIn("S001", r.stderr)
            self.assertEqual(f.read_bytes().decode(), before)

    def test_as_a_git_merge_driver_a_plain_merge_no_longer_interleaves_rows(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            git = lambda *a, check=True: subprocess.run(["git", "-C", d, "-c", "user.email=t@t", "-c", "user.name=t", *a], check=check, capture_output=True, text=True)  # noqa: E731
            (root / "docs/backlog").mkdir(parents=True)
            (root / ".gitattributes").write_text("docs/backlog/backlog.csv merge=backlog-csv -text\n")
            f = root / "docs/backlog/backlog.csv"
            f.write_bytes(text(*EPICS, row("S001", parent="E01")).encode())
            git("init", "-q", "-b", "main")
            git("config", "merge.backlog-csv.driver", f"{sys.executable} {HERE / 'merge_backlog.py'} --driver %O %A %B")
            git("add", "-A")
            git("commit", "-q", "-m", "base")
            git("checkout", "-q", "-b", "release-1")
            f.write_bytes(text(*EPICS, row("S001", parent="E01"), row("E37", "R1"), row("S600", parent="E37", ac="- r1 a\n- r1 b")).encode())
            git("commit", "-qam", "release adds")
            git("checkout", "-q", "main")
            f.write_bytes(text(*EPICS, row("S001", parent="E01"), row("S002", parent="E02", ac="- m a\n- m b\n- m c")).encode())
            git("commit", "-qam", "main adds")
            git("checkout", "-q", "release-1")
            git("merge", "--no-ff", "-m", "merge main", "main")
            self.assertEqual(self.ids(f.read_bytes().decode()), ["E01", "E02", "S001", "S002", "E37", "S600"])
            rows = {r[0]: r for r in mb.parse(f.read_bytes().decode(), "m")}
            self.assertEqual(rows["S002"][4], "- m a\n- m b\n- m c")


if __name__ == "__main__":
    unittest.main()
