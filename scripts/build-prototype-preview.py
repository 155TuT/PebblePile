#!/usr/bin/env python3
"""Build an offline, clickable guide from static Paper JSX and action manifests.

Only literal JSX is accepted. Exported JavaScript is never executed. The guide
simulates navigation and choice states; it has no application backend.
"""

from __future__ import annotations

import argparse
import ast
import html
import json
import re
from pathlib import Path


REPO = Path(__file__).resolve().parents[1]
UNITLESS = set("animationIterationCount aspectRatio borderImageOutset borderImageSlice borderImageWidth boxFlex boxFlexGroup boxOrdinalGroup columnCount columns fillOpacity flex flexGrow flexPositive flexShrink flexNegative flexOrder fontWeight gridArea gridColumn gridColumnEnd gridColumnSpan gridColumnStart gridRow gridRowEnd gridRowSpan gridRowStart lineClamp lineHeight opacity order orphans scale stopOpacity strokeDasharray strokeDashoffset strokeMiterlimit strokeOpacity strokeWidth tabSize widows zIndex zoom WebkitLineClamp".split())
SVG_CASE = set("viewBox preserveAspectRatio gradientUnits gradientTransform patternUnits patternContentUnits patternTransform markerWidth markerHeight markerUnits refX refY textLength lengthAdjust filterUnits primitiveUnits baseFrequency numOctaves stitchTiles targetX targetY stdDeviation edgeMode kernelMatrix kernelUnitLength preserveAlpha externalResourcesRequired pathLength attributeName attributeType calcMode keyTimes keySplines repeatCount repeatDur startOffset".split())
VOID = set("area base br col embed hr img input link meta param source track wbr".split())
FORBIDDEN = {"script", "iframe", "object", "embed", "link", "meta", "base"}


class SourceError(ValueError):
    pass


class Literal:
    """Small, rejecting parser for the literals used by Paper's style exports."""

    def __init__(self, source: str):
        self.s = source
        self.i = 0

    def ws(self):
        while self.i < len(self.s):
            if self.s[self.i].isspace():
                self.i += 1
            elif self.s.startswith("/*", self.i):
                end = self.s.find("*/", self.i + 2)
                if end < 0:
                    raise SourceError("Unclosed comment")
                self.i = end + 2
            elif self.s.startswith("//", self.i):
                end = self.s.find("\n", self.i + 2)
                self.i = len(self.s) if end < 0 else end + 1
            else:
                break

    def string(self):
        start = self.i
        quote = self.s[self.i]
        self.i += 1
        while self.i < len(self.s):
            if self.s[self.i] == "\\":
                self.i += 2
            elif self.s[self.i] == quote:
                self.i += 1
                token = self.s[start:self.i]
                try:
                    return json.loads(token) if quote == '"' else ast.literal_eval(token)
                except (ValueError, SyntaxError) as exc:
                    raise SourceError(f"Invalid literal string: {token[:60]}") from exc
            else:
                self.i += 1
        raise SourceError("Unclosed string")

    def value(self):
        self.ws()
        if self.i >= len(self.s):
            raise SourceError("Missing literal")
        ch = self.s[self.i]
        if ch in "\"'":
            return self.string()
        if ch == "{":
            self.i += 1
            result = {}
            self.ws()
            while self.i < len(self.s) and self.s[self.i] != "}":
                if self.s[self.i] in "\"'":
                    key = self.string()
                else:
                    match = re.match(r"[A-Za-z_$][\w$]*", self.s[self.i:])
                    if not match:
                        raise SourceError("Only literal object keys are supported")
                    key = match.group()
                    self.i += len(key)
                self.ws()
                if self.i >= len(self.s) or self.s[self.i] != ":":
                    raise SourceError(f"Missing ':' after {key}")
                self.i += 1
                result[key] = self.value()
                self.ws()
                if self.s[self.i:self.i + 1] == ",":
                    self.i += 1
                    self.ws()
                elif self.s[self.i:self.i + 1] != "}":
                    raise SourceError("Missing object separator")
            if self.s[self.i:self.i + 1] != "}":
                raise SourceError("Unclosed object")
            self.i += 1
            return result
        if ch == "[":
            self.i += 1
            result = []
            self.ws()
            while self.i < len(self.s) and self.s[self.i] != "]":
                result.append(self.value())
                self.ws()
                if self.s[self.i:self.i + 1] == ",":
                    self.i += 1
                    self.ws()
                elif self.s[self.i:self.i + 1] != "]":
                    raise SourceError("Missing array separator")
            if self.s[self.i:self.i + 1] != "]":
                raise SourceError("Unclosed array")
            self.i += 1
            return result
        match = re.match(r"-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?", self.s[self.i:])
        if match:
            self.i += len(match.group())
            number = float(match.group())
            return int(number) if number.is_integer() else number
        for name, value in (("true", True), ("false", False), ("null", None), ("undefined", None)):
            if self.s.startswith(name, self.i):
                self.i += len(name)
                return value
        raise SourceError(f"Dynamic JavaScript is unsupported: {self.s[self.i:self.i + 70]}")

    def parse(self):
        value = self.value()
        self.ws()
        if self.i != len(self.s):
            raise SourceError(f"Trailing expression: {self.s[self.i:self.i + 60]}")
        return value


def css_name(name: str) -> str:
    if name.startswith("--"):
        return name
    result = re.sub(r"[A-Z]", lambda m: "-" + m.group().lower(), name)
    return "-" + result if result.startswith("ms-") else result


def css_text(value) -> str:
    if not isinstance(value, dict):
        raise SourceError("style must be a literal object")
    parts = []
    for key, item in value.items():
        if item is None or isinstance(item, bool):
            continue
        if isinstance(item, (dict, list)):
            raise SourceError(f"Unsupported CSS value: {key}")
        if isinstance(item, (int, float)):
            item = str(item) + ("px" if item != 0 and key not in UNITLESS and not key.startswith("--") else "")
        item = str(item)
        if re.search(r"url\(\s*['\"]?(?:https?:|blob:|file:)", item, re.I):
            raise SourceError(f"Copy the external fill into a formal asset before export: {key}")
        if "expression(" in item.lower() or "javascript:" in item.lower():
            raise SourceError("Executable CSS is unsupported")
        parts.append(f"{css_name(key)}:{item}")
    return ";".join(parts)


class JSX:
    def __init__(self, source: str):
        source = re.sub(r"^\s*```(?:jsx|tsx|javascript|js)?\s*\n?|\n?```\s*$", "", source)
        match = re.search(r"<(?:[a-zA-Z][\w:-]*\b|>)", source)
        if not match:
            raise SourceError("No JSX element found")
        self.s = source
        self.i = match.start()
        self.ids = set()

    def ws(self):
        while self.i < len(self.s) and self.s[self.i].isspace():
            self.i += 1

    def name(self):
        match = re.match(r"[A-Za-z_:][\w:.-]*", self.s[self.i:])
        if not match:
            raise SourceError(f"Invalid JSX name near {self.s[self.i:self.i + 40]}")
        self.i += len(match.group())
        return match.group()

    def braces(self):
        assert self.s[self.i] == "{"
        start = self.i + 1
        self.i += 1
        depth = 1
        while self.i < len(self.s):
            ch = self.s[self.i]
            if ch in "\"'`":
                quote = ch
                self.i += 1
                while self.i < len(self.s):
                    if self.s[self.i] == "\\":
                        self.i += 2
                    elif self.s[self.i] == quote:
                        self.i += 1
                        break
                    else:
                        self.i += 1
                continue
            if self.s.startswith("/*", self.i):
                end = self.s.find("*/", self.i + 2)
                if end < 0:
                    raise SourceError("Unclosed JSX comment")
                self.i = end + 2
                continue
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    value = self.s[start:self.i]
                    self.i += 1
                    return value
            self.i += 1
        raise SourceError("Unclosed JSX expression")

    def attribute(self):
        self.ws()
        if self.s[self.i] == "{":
            return Literal(self.braces()).parse()
        if self.s[self.i] in "\"'":
            quote = self.s[self.i]
            self.i += 1
            end = self.s.find(quote, self.i)
            if end < 0:
                raise SourceError("Unclosed JSX attribute")
            value = html.unescape(self.s[self.i:end])
            self.i = end + 1
            return value
        raise SourceError("Expected a quoted or literal JSX attribute")

    def element(self):
        assert self.s[self.i] == "<"
        self.i += 1
        fragment = self.s[self.i:self.i + 1] == ">"
        tag = "" if fragment else self.name()
        if tag and (tag[0].isupper() or tag.lower() in FORBIDDEN):
            raise SourceError(f"Unsupported element: {tag}")
        attrs = []
        if not fragment:
            while True:
                self.ws()
                if self.s.startswith("/>", self.i) or self.s[self.i:self.i + 1] == ">":
                    break
                name = self.name()
                if name.lower().startswith("on") or name in {"dangerouslySetInnerHTML", "ref"}:
                    raise SourceError(f"Executable attribute is unsupported: {name}")
                self.ws()
                value = True
                if self.s[self.i:self.i + 1] == "=":
                    self.i += 1
                    value = self.attribute()
                if value is None or value is False or name == "key":
                    continue
                if name == "style":
                    value = css_text(value)
                elif isinstance(value, (dict, list)):
                    raise SourceError(f"Unsupported attribute: {name}")
                if name == "id":
                    if str(value) in self.ids:
                        raise SourceError(f"Duplicate Paper id: {value}")
                    self.ids.add(str(value))
                name = {"className": "class", "htmlFor": "for", "tabIndex": "tabindex", "xlinkHref": "xlink:href", "xmlSpace": "xml:space", "xmlnsXlink": "xmlns:xlink", "defaultValue": "value", "defaultChecked": "checked", "spellCheck": "spellcheck", "readOnly": "readonly", "autoFocus": "autofocus"}.get(name, name)
                if name not in SVG_CASE and not name.startswith(("data-", "aria-")):
                    name = css_name(name)
                if name in {"href", "src", "xlink:href"} and re.match(r"(?:javascript|vbscript|https?|blob|file):", str(value), re.I):
                    raise SourceError(f"External or executable URL must be resolved before export: {value}")
                attrs.append(name if value is True else f'{name}="{html.escape(str(value), quote=True)}"')
        self_closing = self.s.startswith("/>", self.i)
        self.i += 2 if self_closing else 1
        start = "" if fragment else "<" + tag + (" " + " ".join(attrs) if attrs else "") + ">"
        if self_closing or tag in VOID:
            return start + ("" if tag in VOID or fragment else f"</{tag}>")
        children = []
        while self.i < len(self.s):
            if self.s.startswith("</", self.i):
                self.i += 2
                end_tag = "" if fragment else self.name()
                self.ws()
                if end_tag != tag or self.s[self.i:self.i + 1] != ">":
                    raise SourceError(f"Mismatched JSX closing tag for {tag}")
                self.i += 1
                return start + "".join(children) + ("" if fragment else f"</{tag}>")
            if self.s[self.i] == "<":
                children.append(self.element())
            elif self.s[self.i] == "{":
                expression = self.braces().strip()
                if expression.startswith("/*") and expression.endswith("*/"):
                    continue
                value = Literal(expression).parse()
                if value is None or isinstance(value, bool):
                    continue
                if isinstance(value, (dict, list)):
                    raise SourceError("Complex JSX children are unsupported")
                children.append(html.escape(str(value)))
            else:
                end = self.i
                while end < len(self.s) and self.s[end] not in "<{":
                    end += 1
                raw = self.s[self.i:end].replace("\r", "").replace("\t", " ")
                lines = raw.split("\n")
                parts = []
                for index, line in enumerate(lines):
                    if index:
                        line = line.lstrip(" ")
                    if index != len(lines) - 1:
                        line = line.rstrip(" ")
                    if line:
                        parts.append(line)
                children.append(html.escape(html.unescape(" ".join(parts))))
                self.i = end
        raise SourceError(f"Unclosed JSX element: {tag}")

    def parse(self):
        result = self.element()
        tail = self.s[self.i:].strip()
        # A static function/export wrapper is permitted; a second JSX tree is not.
        if tail and not re.fullmatch(r"[\s);}]+", tail):
            raise SourceError(f"Unsupported code after JSX: {tail[:80]}")
        return result, self.ids


def find_jsx(value):
    if isinstance(value, str):
        value = value.strip()
        if value.startswith(("{", "[")):
            try:
                return find_jsx(json.loads(value))
            except (ValueError, TypeError):
                pass
        if re.search(r"<(?:div|svg|section|main|article|>)", value):
            return value
    if isinstance(value, dict):
        for key in ("jsx", "code", "html", "structuredContent", "content", "text"):
            if key in value:
                found = find_jsx(value[key])
                if found:
                    return found
    if isinstance(value, list):
        for item in value:
            found = find_jsx(item)
            if found:
                return found
    return None


def build(manifests: list[Path], output: Path, start: str | None, check: bool):
    screens = []
    defaults = {}
    return_targets = {"memory": "H3", "tags": "L3"}
    raw_sources = {}
    screen_ids = {}
    global_bindings = []
    paper_sources = []
    manifest_start = None
    for manifest in manifests:
        payload = json.loads(manifest.read_text(encoding="utf-8-sig"))
        if isinstance(payload, list):
            payload = {"screens": payload}
        defaults.update(payload.get("defaultState", {}))
        return_targets.update(payload.get("returnTargets", {}))
        global_bindings.extend(payload.get("bindings", []))
        manifest_start = manifest_start or payload.get("start")
        for item in payload.get("paperSources", []) + ([{k: payload[k] for k in ("fileId", "pageId", "tokenHash") if k in payload}] if "fileId" in payload else []):
            if item not in paper_sources:
                paper_sources.append(item)
        for original in payload.get("screens", []):
            screen = dict(original)
            sid = screen.get("id") or screen.get("screenId")
            if not sid or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]*", sid):
                raise SourceError("Each screen needs a stable ASCII id such as H1")
            if sid in raw_sources:
                raise SourceError(f"Duplicate screen id: {sid}")
            source_file = screen.pop("jsxFile", screen.pop("jsxPath", None))
            inline = screen.pop("jsx", None)
            if source_file:
                candidate = Path(source_file)
                if not candidate.is_absolute():
                    candidate = manifest.parent / candidate
                raw = candidate.read_text(encoding="utf-8-sig")
                jsx = find_jsx(raw)
            else:
                jsx = find_jsx(inline)
            if not jsx:
                raise SourceError(f"{sid}: no JSX found")
            try:
                rendered, ids = JSX(jsx).parse()
            except SourceError as exc:
                raise SourceError(f"{sid}: {exc}") from exc
            screen["id"] = sid
            screen["title"] = screen.get("title", screen.get("name", sid))
            screen["width"] = int(screen.get("width", 390))
            screen["height"] = int(screen.get("height", 844))
            if screen["width"] <= 0 or screen["height"] <= 0:
                raise SourceError(f"{sid}: screen dimensions must be positive")
            if "viewportHeight" in screen:
                screen["viewportHeight"] = int(screen["viewportHeight"])
                if screen["viewportHeight"] <= 0:
                    raise SourceError(f"{sid}: viewportHeight must be positive")
            screen["html"] = rendered
            screen["actions"] = screen.get("actions", [])
            action_ids = set()
            for action in screen["actions"]:
                if action.get("nodeId") not in ids:
                    raise SourceError(f"{sid}: action node missing from JSX: {action.get('nodeId')}")
                if action["nodeId"] in action_ids:
                    raise SourceError(f"{sid}: duplicate action node {action['nodeId']}")
                action_ids.add(action["nodeId"])
                if not action.get("label"):
                    raise SourceError(f"{sid}: action requires a visible-purpose label")
            for field in screen.get("fields", []):
                if field.get("nodeId") not in ids or not field.get("stateKey") or not field.get("label"):
                    raise SourceError(f"{sid}: field requires an exported node, stateKey and label")
            for binding in screen.get("bindings", []):
                if binding.get("nodeId") not in ids:
                    raise SourceError(f"{sid}: binding node missing from JSX: {binding.get('nodeId')}")
            screens.append(screen)
            raw_sources[sid] = jsx
            screen_ids[sid] = ids
    if not screens:
        raise SourceError("No screens found")
    known = {s["id"] for s in screens}
    all_ids = set().union(*screen_ids.values())
    for binding in global_bindings:
        if binding.get("nodeId") not in all_ids:
            raise SourceError(f"Global binding node missing from JSX: {binding.get('nodeId')}")
    def walk_actions(actions):
        for action in actions:
            yield action
            yield from walk_actions(action.get("choices", []))
    for screen in screens:
        screen["bindings"] = screen.get("bindings", []) + [binding for binding in global_bindings if binding["nodeId"] in screen_ids[screen["id"]]]
        for action in walk_actions(screen["actions"] + screen.get("demoTransitions", [])):
            if not action.get("label"):
                raise SourceError(f"{screen['id']}: every action or demo transition needs a label")
            targets = [action.get("target")] + list(action.get("targetByState", {}).get("cases", {}).values())
            for target in filter(None, targets):
                if target != "back" and not target.startswith("return:") and target not in known:
                    raise SourceError(f"{screen['id']}: unknown target {target}")
    first = start or manifest_start or ("H1" if "H1" in known else screens[0]["id"])
    if first not in known:
        raise SourceError(f"Unknown start screen: {first}")
    return_targets = {k: v for k, v in return_targets.items() if v in known}
    data = {"title": "PebblePile · 叠石", "start": first, "defaultState": defaults, "returnTargets": return_targets, "paperSources": paper_sources, "screens": screens}
    if check:
        return data
    output.mkdir(parents=True, exist_ok=True)
    source_dir = output / "source"
    source_dir.mkdir(exist_ok=True)
    canonical = {"start": first, "defaultState": defaults, "returnTargets": return_targets, "paperSources": paper_sources, "screens": []}
    for screen in screens:
        sid = screen["id"]
        (source_dir / f"{sid}.jsx").write_text(raw_sources[sid] + "\n", encoding="utf-8")
        source_screen = {k: v for k, v in screen.items() if k != "html"}
        source_screen["jsxFile"] = f"{sid}.jsx"
        canonical["screens"].append(source_screen)
    (source_dir / "manifest.json").write_text(json.dumps(canonical, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    import os
    theme = Path(os.path.relpath(REPO / "assets/theme.css", output)).as_posix()
    components = Path(os.path.relpath(REPO / "assets/components.css", output)).as_posix()
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":")).replace("<", "\\u003c").replace("&", "\\u0026")
    page = TEMPLATE.replace("__THEME__", html.escape(theme, quote=True)).replace("__COMPONENTS__", html.escape(components, quote=True)).replace("__DATA__", payload)
    (output / "index.html").write_text(page, encoding="utf-8")
    return data


TEMPLATE = r'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>PebblePile · 交互原型导览</title><link rel="stylesheet" href="__THEME__"><link rel="stylesheet" href="__COMPONENTS__">
<style>
*{box-sizing:border-box}body{margin:0;background:var(--color-bg);color:var(--color-text);font-family:var(--font-cjk),system-ui,sans-serif;font-size:var(--text-base);font-synthesis:none}
button,select{font:inherit;color:inherit}button,select{min-height:var(--container-touch);border:1px solid var(--color-border-control);border-radius:var(--radius-control);background:var(--color-bg);padding:10px var(--spacing-16)}button{cursor:pointer}button:disabled{cursor:default;opacity:.5}
button:focus-visible,select:focus-visible,[data-prototype-action]:focus-visible,[contenteditable]:focus-visible{outline:2px solid var(--color-focus,#365b44)!important;outline-offset:3px}
.guide-header{max-width:1180px;margin:auto;padding:24px 24px 16px}.guide-header h1{margin:0;font-size:var(--text-2xl);font-weight:var(--font-weight-medium)}.guide-header p{margin:8px 0;color:var(--color-text-secondary);line-height:1.65}
.guide-layout{max-width:1180px;margin:auto;padding:0 24px 32px;display:grid;grid-template-columns:minmax(220px,280px) minmax(0,1fr);gap:32px;align-items:start}
.guide-sidebar{display:flex;flex-direction:column;gap:12px}.guide-tools{display:flex;flex-wrap:wrap;gap:8px}.guide-sidebar select{width:100%}.guide-list{display:flex;flex-direction:column;gap:8px;margin:0;padding:0;list-style:none}.guide-list button{width:100%;text-align:left;border-color:transparent}.guide-list button[aria-current=page]{background:var(--color-primary-soft);box-shadow:var(--shadow-inset)}
.guide-detail{margin:0;line-height:1.7;color:var(--color-text-secondary)}.guide-stage{min-width:0;display:flex;flex-direction:column;align-items:center;gap:16px}.guide-current{margin:0;font-size:var(--text-lg);font-weight:var(--font-weight-medium)}.device-stage{width:min(100%,390px);position:relative;box-shadow:var(--shadow-raised);border-radius:var(--radius-card);background:var(--color-bg)}.device{position:absolute;left:0;top:0;transform-origin:top left;width:390px;height:844px;border-radius:var(--radius-card);overflow:hidden}.device>div{width:100%;height:100%}#screen-root{overflow-y:auto;overflow-x:hidden;overscroll-behavior:contain;scrollbar-width:none}#screen-root::-webkit-scrollbar{display:none}.device svg{flex-shrink:0}
[data-prototype-action]{cursor:pointer}.show-hotspots [data-prototype-action]{outline:2px dashed #365b4499;outline-offset:-2px}
[hidden]{display:none!important}.prototype-field:empty:before{content:attr(data-placeholder);color:var(--color-text-secondary);pointer-events:none}.prototype-field{min-width:1px;white-space:pre-wrap;outline:none;max-width:100%;overflow-wrap:anywhere;color:var(--color-text)!important}[data-prototype-action][aria-disabled=true]{cursor:default}.guide-transitions{display:flex;flex-direction:column;gap:8px}.guide-transitions p{margin:0;font-size:14px;color:#5e6b5d}.guide-dialog-options{display:flex;flex-direction:column;gap:8px;margin-bottom:12px}.guide-prompt{display:block;width:100%;min-height:48px;border:1px solid #788374;border-radius:16px;padding:12px 16px;margin:12px 0 20px;font:inherit;background:#f5f3eb;color:#222c24}
.guide-note{max-width:460px;margin:0;color:var(--color-text-secondary);font-size:var(--text-sm);line-height:1.6}.guide-dialog{border:0;border-radius:var(--radius-card);padding:var(--spacing-24);max-width:min(360px,calc(100vw - 32px));background:var(--color-bg);box-shadow:var(--shadow-overlay)}.guide-dialog::backdrop{background:#222c2452}.guide-dialog h2{font-size:var(--text-xl);font-weight:var(--font-weight-medium);margin:0 0 16px}.guide-dialog p{line-height:1.7;margin:0 0 20px;white-space:pre-line}.guide-dialog button{width:100%}.sr-only{position:absolute;width:1px;height:1px;padding:0;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
@media(max-width:760px){.guide-header{padding:16px}.guide-layout{grid-template-columns:1fr;padding:0 16px 24px;gap:20px}.guide-list{display:none}.guide-sidebar{gap:8px}.guide-detail{font-size:14px}.guide-tools button{flex:1}.guide-stage{gap:12px}.guide-note{font-size:12px}}
@media(prefers-reduced-motion:reduce){*{scroll-behavior:auto!important}}
</style></head><body class="pebblepile-theme">
<header class="guide-header"><h1><span class="pp-latin">PebblePile</span> · 叠石交互原型</h1><p>跟随首页、记录库和记忆纠正流程，或选择任意样张查看。操作只模拟界面状态。</p></header>
<main class="guide-layout"><aside class="guide-sidebar" aria-label="原型导览"><label for="screen-select">选择样张</label><select id="screen-select"></select><div class="guide-tools"><button id="guide-back" type="button">返回</button><button id="guide-reset" type="button">重新开始</button><button id="guide-hotspots" type="button" aria-pressed="false">可点区域</button></div><p id="guide-description" class="guide-detail"></p><div id="guide-transitions" class="guide-transitions"></div><ul id="screen-list" class="guide-list"></ul></aside>
<section class="guide-stage" aria-label="手机原型"><h2 id="screen-title" class="guide-current" tabindex="-1"></h2><div id="device-stage" class="device-stage"><div id="device" class="device"><div id="screen-root"></div></div></div><p class="guide-note">Paper 静态源同步；示例内容为虚构资料。导览在本机运行，不连接模型或保存生活材料。字体优先使用本机已安装的霞鹜文楷与 Leftist；缺失时使用系统回退。</p></section></main>
<div id="announcer" class="sr-only" role="status" aria-live="polite"></div><dialog id="guide-dialog" class="guide-dialog" aria-labelledby="dialog-title"><h2 id="dialog-title">演示说明</h2><p id="dialog-message"></p><label id="prompt-label" for="dialog-input" hidden>标签名称</label><input id="dialog-input" class="guide-prompt" type="text" maxlength="64" hidden><div id="dialog-options" class="guide-dialog-options"></div><button id="dialog-close" type="button">知道了</button></dialog>
<script id="prototype-data" type="application/json">__DATA__</script>
<script>
(()=>{'use strict';
const data=JSON.parse(document.getElementById('prototype-data').textContent), screens=new Map(data.screens.map(s=>[s.id,s]));
const root=document.getElementById('screen-root'),stage=document.getElementById('device-stage'),device=document.getElementById('device'),picker=document.getElementById('screen-select'),title=document.getElementById('screen-title'),description=document.getElementById('guide-description'),backButton=document.getElementById('guide-back'),dialog=document.getElementById('guide-dialog');
let state={...data.defaultState},returns={},trail=[],current=data.start,dialogFocus=null,bindingBases=new Map(),actionBases=new Map(),choiceBases=new Map(),fontBases=[],rootBase=null,scrollPositions={},position=0,pendingFocus=null,pendingMessage=null;
const byId=id=>root.querySelector('#'+CSS.escape(id));
function fit(){const s=screens.get(current),height=s.viewportHeight||s.height,scale=Math.min(1,stage.clientWidth/s.width);device.style.width=s.width+'px';device.style.height=height+'px';device.style.transform='scale('+scale+')';stage.style.height=height*scale+'px'}
function announce(message){document.getElementById('announcer').textContent=message}
function openDialog(text,label){
 const active=document.activeElement;if(!dialog.contains(active))dialogFocus=active;document.getElementById('dialog-title').textContent=label||'演示说明';document.getElementById('dialog-message').textContent=text||'';document.getElementById('dialog-options').replaceChildren();document.getElementById('dialog-input').hidden=true;document.getElementById('prompt-label').hidden=true;document.getElementById('dialog-close').textContent='知道了';if(!dialog.open)dialog.showModal();
}
function message(text,label){openDialog(text,label);document.getElementById('dialog-close').focus()}
function choose(a){
 openDialog(a.message,a.dialogTitle||a.label);document.getElementById('dialog-close').textContent='取消';
 for(const option of a.choices){const button=document.createElement('button');button.type='button';button.textContent=option.label;button.onclick=()=>{dialog.close();act(option)};document.getElementById('dialog-options').append(button)}
 document.getElementById('dialog-options').firstElementChild?.focus();
}
function promptAction(a){
 const spec=a.prompt;openDialog('',spec.title||a.label);const input=document.getElementById('dialog-input');input.hidden=false;document.getElementById('prompt-label').hidden=false;const before=String(state[spec.stateKey]??spec.initialValue??'');input.value=before;const confirm=document.createElement('button');confirm.type='button';confirm.textContent=spec.confirmLabel||'保存';const submit=()=>{const value=input.value.trim();if(!value){document.getElementById('dialog-message').textContent='请输入标签名称。';input.focus();return}if(value===before.trim()){dialog.close();announce('内容未改变');return}dialog.close();act({...a,prompt:null,setState:{...a.setState,[spec.stateKey]:value}})};confirm.onclick=submit;input.onkeydown=event=>{if(event.key==='Enter'){event.preventDefault();submit()}};document.getElementById('dialog-options').append(confirm);document.getElementById('dialog-close').textContent='取消';input.focus();input.select();
}
function format(text){return String(text).replace(/\{([\w]+)\}/g,(_,key)=>String(state[key]??''))}
function refreshState(){
 const s=screens.get(current);
 for(const b of s.bindings||[]){const el=byId(b.nodeId),base=bindingBases.get(b),key=String(state[b.key]);
  if(b.textByValue){el.textContent=Object.hasOwn(b.textByValue,key)?format(b.textByValue[key]):base.text}
  else if(b.textFromState){el.textContent=(b.prefix||'')+String(state[b.textFromState]??'')+(b.suffix||'')}
  else if(b.key&&!b.visibleByValue&&!b.styleByValue){el.textContent=state[b.key]===undefined?base.text:String(state[b.key])}
  if(b.visibleByValue)el.hidden=Object.hasOwn(b.visibleByValue,key)?!b.visibleByValue[key]:base.hidden;
  if(b.styleByValue){const values=b.styleByValue[key]||{};for(const property of Object.keys(base.styles)){const value=values[property]??base.styles[property];property.startsWith('--')?el.style.setProperty(property,value):el.style[property]=value}}
 }
 for(const a of s.actions){const el=byId(a.nodeId);if(a.choice){const chosen=state[a.choice.key]===a.choice.value,visual=choiceBases.get(a.choice.key)?.[chosen?'on':'off'];el.setAttribute('aria-pressed',String(chosen));if(visual){Object.assign(el.style,visual.styles);for(const child of el.children)child.style.color=visual.color}}if(a.toggle)el.setAttribute('aria-checked',String(!!state[a.toggle.key]));else if(a.toggleState)el.setAttribute('aria-expanded',String(!!state[a.toggleState]));if(a.requiresText){const enabled=String(state[a.requiresText]??'').trim().length>0;el.setAttribute('aria-disabled',String(!enabled));el.tabIndex=enabled?0:-1;if(el.tagName==='BUTTON')el.disabled=!enabled;const base=actionBases.get(a),values=enabled?(a.enabledStyles||base):(a.disabledStyles||{opacity:'0.48',boxShadow:'none'});for(const key of Object.keys(base))el.style[key]=values[key]??base[key]}}
 const large=state.textSize==='large';for(const item of fontBases){item.el.style.fontSize=large?'18px':item.fontSize;item.el.style.lineHeight=large?'28px':item.lineHeight}
 if(rootBase){const source=root.firstElementChild;source.style.height=large?'auto':rootBase.height;source.style.minHeight=large?Math.max(s.height,s.viewportHeight||0)+'px':rootBase.minHeight;source.style.overflow=large?'visible':rootBase.overflow}
 for(const field of s.fields||[]){const el=byId(field.nodeId),value=String(state[field.stateKey]??'');if(el.textContent!==value)el.textContent=value;const lineHeight=parseFloat(getComputedStyle(el).lineHeight)||26;el.style.maxHeight=(field.maxHeight||lineHeight*(field.maxLines||4))+'px'}
}
function resolve(mapping,fallback){return mapping?mapping.cases[String(state[mapping.key])]??fallback:fallback}
function navigate(id,options={}){
 if(!screens.has(id))return;const previous=current;if(!options.clearScroll)scrollPositions[previous]=root.scrollTop;
 if(options.remember)returns[options.remember]={id:previous,focus:options.focus,position};
 if(options.push!==false)trail.push({id:previous,focus:options.focus,position});
 if(!options.replace)position++;current=id;render(options.restoreFocus);
 history[options.replace?'replaceState':'pushState']({screen:current,trail:[...trail],position},'', '#'+current);
}
function visitHistory(destination,note){
 scrollPositions[current]=root.scrollTop;
 if(Number.isInteger(destination.position)&&destination.position<position){pendingFocus=destination.focus;pendingMessage=note||null;history.go(destination.position-position);return true}
 navigate(destination.id,{push:false,restoreFocus:destination.focus});return false;
}
function back(){if(!trail.length){if(current!==data.start)navigate(data.start,{push:false});return}visitHistory(trail[trail.length-1])}
function act(a){
 if(a.requiresText&&!String(state[a.requiresText]??'').trim())return;
 if(a.choices){choose(a);return}if(a.prompt){promptAction(a);return}
 if(a.setState)Object.assign(state,a.setState);for(const [target,source] of Object.entries(a.setStateFrom||{}))state[target]=state[source];const toggleKey=a.toggle?.key||a.toggleState;if(toggleKey)state[toggleKey]=!state[toggleKey];if(a.choice)state[a.choice.key]=a.choice.value;refreshState();
 const target=resolve(a.targetByState,a.target),note=resolve(a.messageByState,a.message);let deferred=false;
 if(target==='back')back();
 else if(target?.startsWith('return:')){const key=target.slice(7),destination=returns[key]||{id:data.returnTargets[key]||data.start};deferred=visitHistory(destination,note?{text:note,label:a.messageTitle}:null)}
 else if(target)navigate(target,{remember:a.rememberReturn,focus:a.nodeId});
 if(note&&!deferred)message(note,a.messageTitle||'演示说明');else if(a.choice)announce(a.label+'，已选择');
}
function render(focusId){
 const s=screens.get(current);Object.assign(state,s.onEnterState||{});root.innerHTML=s.html;title.textContent=s.id+' · '+s.title;picker.value=s.id;description.textContent=s.description||'';
 const source=root.firstElementChild;rootBase=source?{height:source.style.height,minHeight:source.style.minHeight,overflow:source.style.overflow}:null;fontBases=[];for(const el of root.querySelectorAll('[id]')){const c=getComputedStyle(el);if(parseFloat(c.fontSize)===16&&parseFloat(c.lineHeight)===26&&parseFloat(c.fontWeight)<=400&&Array.from(el.childNodes).some(n=>n.nodeType===Node.TEXT_NODE&&n.textContent.trim()))fontBases.push({el,fontSize:el.style.fontSize,lineHeight:el.style.lineHeight})}
 for(const button of document.querySelectorAll('[data-screen-id]'))button.setAttribute('aria-current',button.dataset.screenId===current?'page':'false');backButton.disabled=!trail.length&&current===data.start;
 choiceBases=new Map();for(const a of s.actions.filter(a=>a.choice)){const key=a.choice.key,initial=s.onEnterState?.[key]??data.defaultState[key],el=byId(a.nodeId),group=choiceBases.get(key)||{};group[a.choice.value===initial?'on':'off']={styles:Object.fromEntries(['backgroundColor','borderColor','borderWidth','borderStyle','boxShadow'].map(p=>[p,el.style[p]])),color:el.firstElementChild?.style.color||''};choiceBases.set(key,group)}
 actionBases=new Map();for(const a of s.actions){const el=byId(a.nodeId);el.dataset.prototypeAction='';el.setAttribute('aria-label',a.label);if(a.requiresText){const keys=[...new Set(['opacity','boxShadow',...Object.keys(a.enabledStyles||{}),...Object.keys(a.disabledStyles||{})])];actionBases.set(a,Object.fromEntries(keys.map(key=>[key,el.style[key]])))}if(!['BUTTON','A','INPUT','SELECT','TEXTAREA'].includes(el.tagName)){el.setAttribute('role',a.role||(a.toggle?'switch':'button'));el.tabIndex=0;el.addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();event.stopPropagation();act(a)}})}el.addEventListener('click',event=>{event.preventDefault();event.stopPropagation();act(a)})}
 bindingBases=new Map();for(const b of s.bindings||[]){const el=byId(b.nodeId),properties=[...new Set(Object.values(b.styleByValue||{}).flatMap(x=>Object.keys(x)))];bindingBases.set(b,{text:el.textContent,hidden:el.hidden,styles:Object.fromEntries(properties.map(p=>[p,p.startsWith('--')?el.style.getPropertyValue(p):el.style[p]]))})}
 for(const field of s.fields||[]){const el=byId(field.nodeId);el.contentEditable='plaintext-only';el.classList.add('prototype-field');el.dataset.placeholder=field.placeholder||'';el.setAttribute('role','textbox');el.setAttribute('aria-label',field.label);el.setAttribute('aria-multiline','true');el.tabIndex=0;const lineHeight=parseFloat(getComputedStyle(el).lineHeight)||26;el.style.maxHeight=(field.maxHeight||lineHeight*(field.maxLines||4))+'px';el.style.overflowY='auto';if(state[field.stateKey]===undefined)state[field.stateKey]=field.initialValue??el.textContent;el.textContent=state[field.stateKey];el.addEventListener('input',()=>{state[field.stateKey]=el.textContent;refreshState()})}
 const transitions=document.getElementById('guide-transitions');transitions.replaceChildren();if(s.demoTransitions?.length){const label=document.createElement('p');label.textContent='模拟状态变化';transitions.append(label);for(const a of s.demoTransitions){const button=document.createElement('button');button.type='button';button.textContent=a.label;button.dataset.demoTransition='';button.onclick=()=>act(a);transitions.append(button)}}
 refreshState();fit();root.scrollTop=scrollPositions[current]||0;announce(s.title);if(!dialog.open)(focusId&&byId(focusId)||title).focus({preventScroll:true});
}
for(const s of data.screens){picker.add(new Option(s.id+' · '+s.title,s.id));const li=document.createElement('li'),button=document.createElement('button');button.type='button';button.dataset.screenId=s.id;button.textContent=s.id+' · '+s.title;button.onclick=()=>navigate(s.id);li.append(button);document.getElementById('screen-list').append(li)}
dialog.addEventListener('keydown',event=>{if(event.key!=='Tab')return;const stops=Array.from(dialog.querySelectorAll('button,input,[tabindex="0"]')).filter(el=>!el.hidden&&!el.disabled&&el.getClientRects().length),first=stops[0],last=stops[stops.length-1];if(!first)return;if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus()}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus()}});
picker.onchange=()=>navigate(picker.value);backButton.onclick=back;document.getElementById('guide-reset').onclick=()=>{state={...data.defaultState};returns={};trail=[];scrollPositions={};navigate(data.start,{push:false,replace:true,clearScroll:true})};document.getElementById('guide-hotspots').onclick=event=>{const enabled=document.body.classList.toggle('show-hotspots');event.currentTarget.setAttribute('aria-pressed',String(enabled))};document.getElementById('dialog-close').onclick=()=>dialog.close();dialog.addEventListener('close',()=>{if(!dialog.open&&dialogFocus?.isConnected)dialogFocus.focus()});dialog.addEventListener('click',event=>{if(event.target===dialog){const r=dialog.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)dialog.close()}});document.addEventListener('keydown',event=>{if(event.key==='Escape'&&!dialog.open){event.preventDefault();back()}});window.addEventListener('popstate',event=>{const id=event.state?.screen||location.hash.slice(1);if(screens.has(id)){scrollPositions[current]=root.scrollTop;current=id;trail=event.state?.trail||[];position=event.state?.position||0;render(pendingFocus);pendingFocus=null;if(pendingMessage){message(pendingMessage.text,pendingMessage.label);pendingMessage=null}}});new ResizeObserver(fit).observe(stage);const linked=location.hash.slice(1);if(screens.has(linked))current=linked;history.replaceState({screen:current,trail:[],position},'','#'+current);render();
})();
</script></body></html>
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", action="append", type=Path, help="Input manifest; may be repeated")
    parser.add_argument("--output", type=Path, default=REPO / "assets/prototype")
    parser.add_argument("--start", help="Initial screen id; defaults to H1 or the first screen")
    parser.add_argument("--check", action="store_true", help="Validate inputs without writing files")
    args = parser.parse_args()
    manifests = args.manifest or [REPO / "assets/prototype/source/manifest.json"]
    try:
        result = build(manifests, args.output, args.start, args.check)
    except (SourceError, OSError, ValueError) as exc:
        parser.error(str(exc))
    print(f"{'Validated' if args.check else 'Built'} {len(result['screens'])} screens, {sum(len(s['actions']) for s in result['screens'])} actions")


if __name__ == "__main__":
    main()
