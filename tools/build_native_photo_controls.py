"""Derive Photos controls from probe_native_photos.m output; no device I/O."""
import json
import sys
from pathlib import Path


def choices(rows, slot):
    result = []
    seen = set()
    for row in rows:
        token = row["configuration"]["customization"]["color"]["slots"][slot]
        if token in seen:
            continue
        seen.add(token)
        result.append({"value": token, "labels": {"en": row["label"]}})
    return result


def controls(probe):
    styles = probe["stylePalettes"]
    time_values = None
    groups = {}
    for style, group in styles.items():
        baseline = group["baseline"]["customData"]
        rows = group["slots"]["style-color"]
        changed = {
            key for row in rows
            for key, value in row["configuration"]["customData"].items()
            if value != baseline.get(key)
        }
        if len(changed) > 1:
            raise ValueError(f"Multiple native remembered keys for {style}: {changed}")
        values = choices(rows, "style-color")
        groups[style] = {"values": values}
        if changed:
            key = changed.pop()
            if any(row["configuration"]["customData"][key] !=
                   row["configuration"]["customization"]["color"]["slots"]["style-color"]
                   for row in rows):
                raise ValueError(f"Native remembered palette mismatch: {style}")
            groups[style]["rememberPath"] = ["customData", key]
        actual_time_values = choices(group["slots"]["time-color"], "time-color")
        if time_values is not None and actual_time_values != time_values:
            raise ValueError("Time palette depends on style; use conditional groups")
        time_values = actual_time_values
    return [
        {"path": ["customization", "color", "slots", "style-color"],
         "selectorPath": ["customization", "style"],
         "followsSelector": True, "titles": {"en": "Photo Color"},
         "groups": groups},
        {"path": ["customization", "color", "slots", "time-color"],
         "titles": {"en": "Time Color"}, "groups": {"*": {"values": time_values}}},
    ]


if __name__ == "__main__":
    probe_path, profile_path = map(Path, sys.argv[1:])
    probe = json.loads(probe_path.read_text())
    profile = json.loads(profile_path.read_text())
    target = next(row for row in profile["templates"]
                  if row["family"] == "bundle:com.apple.NTKParmesanFaceBundle")
    target["controls"] = controls(probe)
    profile_path.write_text(json.dumps(profile, ensure_ascii=False, indent=2) + "\n")
