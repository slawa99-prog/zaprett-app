#!/usr/bin/env python3
"""Build the standalone DEX JAR and Windows package using ECJ and official Android D8.

Example: python3 build.py --android-jar /path/android.jar --ecj-jar /path/ecj.jar
                         --d8-jar /path/r8.jar
Java must be installed. Android SDK/ECJ/R8 dependencies are not downloaded by this script.
"""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import zipfile


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def archive(path, files):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as output:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, (2026, 10, 9, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            output.writestr(info, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-jar", required=True, type=Path)
    parser.add_argument("--ecj-jar", required=True, type=Path)
    parser.add_argument("--d8-jar", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    build = root / "build"
    dist = root / "dist"
    if build.exists():
        shutil.rmtree(build)
    classes = build / "classes"
    tests = build / "tests"
    dex = build / "dex"
    for folder in (classes, tests, dex, dist):
        folder.mkdir(parents=True, exist_ok=True)

    sources = sorted((root / "src").rglob("*.java"))
    run("java", "-jar", args.ecj_jar, "-1.8", "-warn:none", "-cp", args.android_jar,
        "-d", classes, *sources)
    run("java", "-jar", args.ecj_jar, "-1.8", "-warn:none", "-cp", classes,
        "-d", tests, *sorted((root / "test").rglob("*.java")))
    import os
    run("java", "-cp", str(classes) + os.pathsep + str(tests),
        "com.slawa.ethernetlink.tools.TargetPolicyTest")

    input_jar = build / "classes.jar"
    archive(input_jar, {p.relative_to(classes).as_posix(): p.read_bytes()
                        for p in classes.rglob("*.class")})
    run("java", "-cp", args.d8_jar, "com.android.tools.r8.D8", "--min-api", "31",
        "--lib", args.android_jar, "--output", dex, input_jar)
    dex_bytes = (dex / "classes.dex").read_bytes()
    if not dex_bytes.startswith(b"dex\n"):
        raise RuntimeError("D8 did not produce a DEX file")
    helper = dist / "ethernet-usb-access.jar"
    archive(helper, {"classes.dex": dex_bytes})

    files = {"ethernet-usb-access.jar": helper.read_bytes()}
    for name in ("START.cmd", "README-ru.txt"):
        # Windows-friendly encoding/newlines; CMD itself contains only ASCII.
        text = (root / name).read_text(encoding="utf-8")
        data = text.replace("\n", "\r\n").encode("utf-8")
        if name.endswith(".txt"):
            data = b"\xef\xbb\xbf" + data
        files[name] = data
    files["SHA256SUMS.txt"] = ("\r\n".join(
        hashlib.sha256(data).hexdigest() + "  " + name for name, data in sorted(files.items())
    ) + "\r\n").encode("ascii")
    release = dist / "EthernetLink-Samsung-USB-Access-1.0-test2.zip"
    archive(release, files)
    print("Package:", release)
    print("SHA256:", hashlib.sha256(release.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
