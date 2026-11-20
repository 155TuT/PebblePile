"""Verify the shared dinner inputs and their Unicode code-point references."""

import json
from pathlib import Path


def check(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    directory = Path(__file__).resolve().parent
    fixtures = json.loads((directory / "fixtures.json").read_text(encoding="utf-8"))
    expected = json.loads((directory / "expected.json").read_text(encoding="utf-8"))
    source = (directory.parents[2] / fixtures["source"]).read_text(encoding="utf-8")
    rows = {}
    for line in source.splitlines():
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) == 3:
            rows[cells[0]] = cells[2]

    for key in ("datasetId", "datasetVersion"):
        check(fixtures[key] == expected[key], f"{key}: versions must match")
    for conventions in (fixtures["conventions"], expected["spans"]["conventions"]):
        check(conventions["offsetUnit"] == "unicode-code-point", "offset unit")
        check(conventions["offsetBase"] == 0 and conventions["spanEnd"] == "exclusive", "offset bounds")

    for group in ("records", "queries", "corrections", "outcomes"):
        for item in fixtures[group]:
            identifier = item["id"]
            original = "Q01" if identifier == "Q01b" else identifier
            check(rows[original] == item["text"], f"{identifier}: differs from #2")

    spans_by_record = expected["spans"]["records"]
    record_ids = [record["id"] for record in fixtures["records"]]
    check(len(record_ids) == len(set(record_ids)) and set(record_ids) == set(spans_by_record), "record IDs")
    count = 0
    for record in fixtures["records"]:
        identifier = record["id"]
        text = record["text"]
        check(len(text) == record["length"], f"{identifier}: text length")
        references = spans_by_record[identifier]
        check(len(references) == len(record["spans"]), f"{identifier}: span count")
        for span, reference in zip(record["spans"], references):
            start, end = span["start"], span["end"]
            check(0 <= start < end <= len(text), f"{identifier}: invalid span [{start},{end})")
            check(text[start:end] == span["text"], f"{identifier}: wrong quote at [{start},{end})")
            check(reference == {key: span[key] for key in ("start", "end", "text")}, f"{identifier}: expected span")
            count += 1

    probe = fixtures["offsetExample"]
    text, start, end = probe["text"], probe["start"], probe["end"]
    check(len(text[:start].encode("utf-16-le")) // 2 != start, "probe must distinguish code points from UTF-16")
    check(text[start:end] == probe["quote"], "non-BMP quote")
    print(f"{fixtures['datasetId']} {fixtures['datasetVersion']}: {count} spans and Unicode probe passed")


if __name__ == "__main__":
    main()
