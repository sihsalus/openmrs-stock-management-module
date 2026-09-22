"""Prepare and inspect a disposable distribution build containing the PR OMOD.

Keep the distribution's pinned dependencies and canonical build instructions.
Install the override inside its Maven cache mount, before that build runs.
This checks packaging; it does not start OpenMRS or certify a database migration.
"""

import argparse
import hashlib
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile


CACHE_RUN = "RUN --mount=type=cache,target=/root/.m2/repository \\\n"
COPY_BACKEND = "COPY backend ./backend/\n"
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.]+)?")


def module_version(omod):
    with zipfile.ZipFile(omod) as archive:
        config = ET.fromstring(archive.read("config.xml"))
    version = config.findtext("version", "")
    if config.findtext("id") != "stockmanagement" or not VERSION.fullmatch(version):
        raise ValueError("Expected a stockmanagement OMOD with a literal version")
    return version


def prepare(distribution, omod):
    version = module_version(omod)
    backend = distribution / "backend"
    pom_path = backend / "pom.xml"
    dockerfile = (backend / "Dockerfile").read_text()
    pom = pom_path.read_text()
    properties = ET.fromstring(pom).findall(
        "{http://maven.apache.org/POM/4.0.0}properties/"
        "{http://maven.apache.org/POM/4.0.0}stockmanagement.version"
    )
    if len(properties) != 1:
        raise ValueError("Expected one distribution stockmanagement version property")
    if dockerfile.count(CACHE_RUN) != 1 or dockerfile.count(COPY_BACKEND) != 1:
        raise ValueError("Distribution build layout changed; review the override")
    if dockerfile.index(COPY_BACKEND) > dockerfile.index(CACHE_RUN):
        raise ValueError("Backend context must be copied before the Maven build")
    overlay = backend / "Dockerfile.stockmanagement-ci"
    artifact = backend / "ci-stockmanagement.omod"
    if overlay.exists() or artifact.exists():
        raise ValueError("Use a fresh disposable distribution checkout")
    updated_pom, replacements = re.subn(
        r"<stockmanagement.version>[^<]+</stockmanagement.version>",
        f"<stockmanagement.version>{version}</stockmanagement.version>", pom,
    )
    if replacements != 1:
        raise ValueError("Expected one literal stockmanagement version element")
    install = (
        "    mvn --batch-mode --no-transfer-progress \\\n"
        "      org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \\\n"
        "      -Dfile=backend/ci-stockmanagement.omod \\\n"
        "      -DgroupId=io.github.proyecto-santaclotilde \\\n"
        f"      -DartifactId=stockmanagement-omod -Dversion={version} \\\n"
        "      -Dpackaging=jar -DgeneratePom=true && \\\n"
    )
    # Validate everything before changing this disposable checkout.
    shutil.copyfile(omod, artifact)
    pom_path.write_text(updated_pom)
    overlay.write_text(dockerfile.replace(CACHE_RUN, CACHE_RUN + install))
    print(f"Prepared distribution build with stockmanagement {version}")


def verify(modules, omod):
    expected_version = module_version(omod)
    candidates = []
    for path in modules.glob("*.omod"):
        with zipfile.ZipFile(path) as archive:
            config = ET.fromstring(archive.read("config.xml"))
        if config.findtext("id") == "stockmanagement":
            candidates.append(path)
    if len(candidates) != 1:
        raise ValueError("Expected exactly one packaged stockmanagement module")
    actual = candidates[0]
    if module_version(actual) != expected_version:
        raise ValueError("Packaged stockmanagement version differs from the PR")
    digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    if digest(actual) != digest(omod):
        raise ValueError("Packaged stockmanagement bytes differ from the PR")
    print(f"Verified stockmanagement {expected_version}, SHA256 {digest(actual)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_command = commands.add_parser("prepare")
    prepare_command.add_argument("--distribution", type=Path, required=True)
    prepare_command.add_argument("--omod", type=Path, required=True)
    verify_command = commands.add_parser("verify")
    verify_command.add_argument("--modules", type=Path, required=True)
    verify_command.add_argument("--omod", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.distribution, args.omod)
    else:
        verify(args.modules, args.omod)
