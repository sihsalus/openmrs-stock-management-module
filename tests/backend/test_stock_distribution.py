import contextlib
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

from stock_distribution import CACHE_RUN, prepare, verify


class StockDistributionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.backend = self.root / "backend"
        self.backend.mkdir()
        self.original = (
            "FROM distribution AS dev\nCOPY backend ./backend/\n" + CACHE_RUN
            + "    bash backend/bin/build-source-omods.sh package && \\\n"
            + "    mvn $MVN_ARGS_SETTINGS $MVN_ARGS\nFROM runtime\n"
        )
        (self.backend / "Dockerfile").write_text(self.original)
        (self.backend / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><properties>'
            '<stockmanagement.version>3.1.1</stockmanagement.version>'
            '</properties></project>'
        )
        self.omod = self.root / "pr.omod"
        self.make_omod(self.omod)

    @staticmethod
    def make_omod(path, version="3.1.2-SNAPSHOT", payload="PR code"):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("config.xml", f"<module><id>stockmanagement</id><version>{version}</version></module>")
            archive.writestr("payload", payload)

    def test_prepare_keeps_canonical_build_and_installs_inside_its_cache_mount(self):
        prepare(self.root, self.omod)
        generated = (self.backend / "Dockerfile.stockmanagement-ci").read_text()
        self.assertEqual(self.original, (self.backend / "Dockerfile").read_text())
        self.assertLess(generated.index(CACHE_RUN), generated.index("maven-install-plugin"))
        self.assertLess(generated.index("maven-install-plugin"), generated.index("build-source-omods.sh"))
        self.assertTrue(generated.endswith(self.original.split(CACHE_RUN)[1]))
        self.assertEqual(self.omod.read_bytes(), (self.backend / "ci-stockmanagement.omod").read_bytes())
        self.assertIn("<stockmanagement.version>3.1.2-SNAPSHOT</stockmanagement.version>",
                      (self.backend / "pom.xml").read_text())

    def test_changed_layout_fails_without_modifying_the_checkout(self):
        (self.backend / "Dockerfile").write_text(self.original.replace(CACHE_RUN, "RUN mvn install\n"))
        before = (self.backend / "pom.xml").read_text()
        with self.assertRaisesRegex(ValueError, "layout changed"):
            prepare(self.root, self.omod)
        self.assertEqual(before, (self.backend / "pom.xml").read_text())
        self.assertFalse((self.backend / "ci-stockmanagement.omod").exists())

    def test_version_cannot_inject_build_instructions(self):
        self.make_omod(self.omod, version="3.1.2; bad-command")
        with self.assertRaisesRegex(ValueError, "literal version"):
            prepare(self.root, self.omod)

    def test_packaged_artifact_must_have_the_exact_pr_bytes(self):
        modules = self.root / "modules"
        modules.mkdir()
        packaged = modules / "stockmanagement.omod"
        packaged.write_bytes(self.omod.read_bytes())
        verify(modules, self.omod)
        self.make_omod(packaged, payload="different code, same version")
        with self.assertRaisesRegex(ValueError, "bytes differ"):
            verify(modules, self.omod)

    def test_missing_or_duplicate_stockmanagement_is_rejected(self):
        modules = self.root / "modules"
        modules.mkdir()
        with self.assertRaisesRegex(ValueError, "exactly one"):
            verify(modules, self.omod)
        (modules / "first.omod").write_bytes(self.omod.read_bytes())
        (modules / "second.omod").write_bytes(self.omod.read_bytes())
        with self.assertRaisesRegex(ValueError, "exactly one"):
            verify(modules, self.omod)


if __name__ == "__main__":
    with contextlib.redirect_stdout(io.StringIO()):
        unittest.main()
