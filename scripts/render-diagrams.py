#!/usr/bin/env python3
"""Render the system and database diagrams with the official PlantUML JAR."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]


def find_java():
    executable = "java.exe" if os.name == "nt" else "java"
    if os.environ.get("JAVA_HOME"):
        candidate = Path(os.environ["JAVA_HOME"]) / "bin" / executable
        if candidate.is_file():
            return candidate
    if os.name == "nt" and os.environ.get("ProgramFiles"):
        candidate = Path(os.environ["ProgramFiles"]) / "Java" / "jdk-17" / "bin" / executable
        if candidate.is_file():
            return candidate
    return shutil.which(executable)


def main():
    parser = argparse.ArgumentParser(description="将 docs/diagrams 中的 PlantUML 源文件渲染为 SVG。")
    parser.add_argument("--plantuml-jar", type=Path, default=ROOT / ".build/tools/plantuml.jar",
                        help="官方 PlantUML JAR 的路径（默认 .build/tools/plantuml.jar）")
    parser.add_argument("--java", help="Java 可执行文件的路径（默认优先使用 JAVA_HOME）")
    args = parser.parse_args()
    if not args.plantuml_jar.is_file():
        sys.exit("请从 https://github.com/plantuml/plantuml/releases 下载官方 JAR，"
                 "放入 .build/tools/plantuml.jar，或用 --plantuml-jar 指定路径。")
    java = args.java or find_java()
    if not java:
        sys.exit("请安装 OpenJDK 17，配置 JAVA_HOME 或用 --java 指定 Java。")
    sources = sorted((ROOT / "docs/diagrams").glob("*.puml"))
    if not sources:
        sys.exit("docs/diagrams 中没有 PlantUML 源文件。")
    try:
        result = subprocess.run([java, "-Djava.awt.headless=true", "-jar", args.plantuml_jar,
                                 "-charset", "UTF-8", "-failfast2", "-tsvg", "-nometadata", *sources])
    except OSError as error:
        sys.exit(f"无法启动 Java。{error}")
    if result.returncode:
        return result.returncode
    print(f"已生成 {len(sources)} 张 SVG。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
