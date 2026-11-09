"""Render the repository's indented Mermaid mindmap as a three-column SVG.

Only the mindmap syntax used by information-architecture.mmd is accepted.
Labels and hierarchy come from that source; colors and fonts use the VI tokens.
"""
from pathlib import Path
import json
import re
import unicodedata
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/diagrams/information-architecture.mmd"
OUTPUT = SOURCE.with_suffix(".svg")
NS = "http://www.w3.org/2000/svg"
ET.register_namespace("", NS)

def element(parent, tag, **attrs):
    return ET.SubElement(parent, "{%s}%s" % (NS, tag), {k.replace("_", "-"): str(v) for k, v in attrs.items()})

def parse():
    lines = [line.rstrip() for line in SOURCE.read_text(encoding="utf-8").splitlines() if line.strip()]
    assert lines[0] == "mindmap"
    match = re.fullmatch(r"  root\(\((.+)\)\)", lines[1])
    assert match, "Expected two-space root((...))"
    root = {"label": match.group(1), "children": []}
    stack = [(2, root)]
    for line in lines[2:]:
        indent = len(line) - len(line.lstrip())
        assert indent >= 4 and indent % 2 == 0
        while stack[-1][0] >= indent:
            stack.pop()
        assert indent == stack[-1][0] + 2
        node = {"label": line.strip(), "children": []}
        stack[-1][1]["children"].append(node)
        stack.append((indent, node))
    assert len(root["children"]) == 3, "The diagram distinguishes entries, capabilities and system extensions."
    return root

def wrap(text, width):
    lines, current, units = [], "", 0
    for ch in text:
        size = 1 if unicodedata.east_asian_width(ch) in "WF" else 0.56
        if units + size > width and current:
            lines.append(current)
            current, units = "", 0
        current += ch
        units += size
    if current:
        lines.append(current)
    return lines

def draw_text(svg, value, x, y, size=18, color="#222C24", weight=400, anchor="start"):
    node = element(svg, "text", x=x, y=y, font_family="LXGW WenKai", font_size=size, font_weight=weight, fill=color, text_anchor=anchor, style="font-synthesis:none")
    for part in re.split(r"([A-Za-z0-9][A-Za-z0-9 /+—.:-]*)", value):
        if not part:
            continue
        span = element(node, "tspan", **({"font_family":"'Leftist Mono Serif', 'Leftist Mono Sans'", "font_weight":400} if re.match(r"[A-Za-z0-9]", part) else {}))
        span.text = part
    return node

def main():
    tree = parse()
    tokens = {t["name"]: t["value"] for t in json.loads((ROOT/"assets/design-tokens.json").read_text(encoding="utf-8"))["paperTokens"]}
    bg, text, muted, primary, line = (tokens[k] for k in ("--color-bg", "--color-text", "--color-text-secondary", "--color-primary", "--color-border"))
    width, margin, gap, column = 1440, 24, 24, 448
    svg = ET.Element("{%s}svg" % NS, {"width":str(width), "role":"img", "aria-labelledby":"ia-title ia-desc"})
    element(svg, "title", id="ia-title").text = "叠石：日常入口、内容与处理能力、系统与扩展"
    element(svg, "desc", id="ia-desc").text = "由 information-architecture.mmd 生成。语义标签由 Agent 与本地算法生成，用于组织和检索内容。"
    background = element(svg, "rect", x=0, y=0, width=width, height=1, rx=24, fill=bg)
    root_lines = tree["label"].split("<br/>")
    draw_text(svg, root_lines[0], width/2, 48, 28, text, 500, "middle")
    draw_text(svg, root_lines[1], width/2, 80, 20, muted, 400, "middle")
    centers = [margin+column/2+i*(column+gap) for i in range(3)]
    element(svg, "path", d=f"M {width/2} 96 V 112 M {centers[0]} 112 H {centers[-1]}", stroke=primary, stroke_width=1.5, fill="none")
    max_y = 0
    for i, group in enumerate(tree["children"]):
        x = margin+i*(column+gap)
        center = centers[i]
        element(svg, "path", d=f"M {center} 112 V 132", stroke=primary, stroke_width=1.5, fill="none")
        element(svg, "rect", x=x, y=132, width=column, height=54, rx=16, fill=tokens["--color-primary-soft"] if i == 0 else tokens["--color-surface-muted"])
        draw_text(svg, group["label"], x+20, 168, 24, primary, 500)
        y = 220
        for section in group["children"]:
            title_lines = wrap(section["label"], 20)
            for title in title_lines:
                draw_text(svg, title, x+20, y, 20, text, 500)
                y += 28
            for leaf in section["children"]:
                assert not leaf["children"], "Add a layout rule for deeper nodes."
                element(svg, "circle", cx=x+24, cy=y+10, r=2.5, fill=primary)
                for label in wrap(leaf["label"], 22):
                    draw_text(svg, label, x+38, y+16, 17, muted)
                    y += 27
            y += 18
            element(svg, "path", d=f"M {x+20} {y-6} H {x+column-20}", stroke=line, stroke_width=1)
            y += 18
        max_y = max(max_y,y)
    footer_y = max_y+12
    draw_text(svg, "日常入口负责操作；记录与事项承载内容；标签、个人模型与持久任务共同支撑处理。", 44, footer_y, 18, muted)
    height = footer_y+32
    svg.set("height",str(height))
    svg.set("viewBox",f"0 0 {width} {height}")
    background.set("height",str(height))
    ET.indent(svg,space="  ")
    OUTPUT.write_text(ET.tostring(svg,encoding="unicode")+"\n",encoding="utf-8")
    print(f"Generated {OUTPUT.relative_to(ROOT)} ({width} x {height})")

if __name__ == "__main__":
    main()

