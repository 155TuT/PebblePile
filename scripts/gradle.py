#!/usr/bin/env python3
"""Run the standalone project's pinned Gradle Wrapper from any directory."""

import os
from pathlib import Path
import subprocess
import sys


PROJECT = Path(__file__).resolve().parents[1] / "config" / "gradle"


def main(args):
    gradle_args = ["-p", str(PROJECT), *args]
    if os.name != "nt":
        return subprocess.call([PROJECT / "gradlew", *gradle_args])

    # Pass user arguments straight to Java. A .bat file would let cmd.exe
    # interpret characters such as & and % inside Gradle property values.
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / "java.exe") if java_home else "java.exe"
    wrapper = PROJECT / "gradle" / "wrapper" / "gradle-wrapper.jar"
    launch = subprocess.list2cmdline([java, "-Xmx64m", "-Xms64m"])
    options = " ".join(os.environ.get(name, "") for name in ("JAVA_OPTS", "GRADLE_OPTS"))
    arguments = subprocess.list2cmdline([
        "-Dorg.gradle.appname=gradlew", "-classpath", str(wrapper),
        "org.gradle.wrapper.GradleWrapperMain", *gradle_args,
    ])
    return subprocess.call(f"{launch} {options} {arguments}", executable=java)


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except OSError as error:
        sys.exit(f"无法启动 Gradle Wrapper。请检查 JAVA_HOME 和 config/gradle 中的 Wrapper。{error}")
