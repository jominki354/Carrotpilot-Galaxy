#!/usr/bin/env python3
"""
Build a local snapshot of Hyundai-related data from an existing openpilot tree.

This script intentionally avoids importing openpilot/opendbc Python packages
so it can run without capnp/python dependencies.
"""

from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path


FW_MODEL_RE = re.compile(r"^\s*CAR\.([A-Z0-9_]+)\s*:\s*\{", re.MULTILINE)
CAR_CLASS_START_RE = re.compile(r"^class CAR\(Platforms\):\s*$", re.MULTILINE)
CAR_DEF_RE = re.compile(r"^\s{2}([A-Z0-9_]+)\s*=\s*Hyundai", re.MULTILINE)
ROUTE_RE = re.compile(
  r"CarTestRoute\(\s*\"([^\"]+)\"\s*,\s*HYUNDAI\.([A-Z0-9_]+)",
)


def parse_hyundai_fw_models(hyundai_fingerprints: Path) -> list[str]:
  text = hyundai_fingerprints.read_text(encoding="utf-8")
  return sorted(set(FW_MODEL_RE.findall(text)))


def parse_hyundai_platform_models(hyundai_values: Path) -> list[str]:
  text = hyundai_values.read_text(encoding="utf-8")
  m = CAR_CLASS_START_RE.search(text)
  if not m:
    return []
  class_text = text[m.start():]
  return sorted(set(CAR_DEF_RE.findall(class_text)))


def parse_hyundai_test_routes(routes_py: Path) -> dict[str, list[str]]:
  text = routes_py.read_text(encoding="utf-8")
  out: dict[str, list[str]] = defaultdict(list)
  for route, model in ROUTE_RE.findall(text):
    out[model].append(route)
  return {k: sorted(set(v)) for k, v in sorted(out.items())}


def main() -> int:
  parser = argparse.ArgumentParser(description="Build Hyundai snapshot from openpilot/opendbc files")
  parser.add_argument(
    "--openpilot-root",
    default=r"e:\Carrotpilot_Galaxy\Carrotpilot",
    help="Path containing opendbc_repo/ and selfdrive/",
  )
  parser.add_argument(
    "--output",
    default=r"e:\Carrotpilot_Galaxy\Carrotpilot_galaxy\offline_porting\data\hyundai_openpilot_snapshot.json",
    help="Output JSON path",
  )
  args = parser.parse_args()

  root = Path(args.openpilot_root)
  hyundai_fingerprints = root / "opendbc_repo" / "opendbc" / "car" / "hyundai" / "fingerprints.py"
  hyundai_values = root / "opendbc_repo" / "opendbc" / "car" / "hyundai" / "values.py"
  routes_py = root / "opendbc_repo" / "opendbc" / "car" / "tests" / "routes.py"

  missing = [p for p in (hyundai_fingerprints, hyundai_values, routes_py) if not p.exists()]
  if missing:
    raise FileNotFoundError(f"Missing required files: {missing}")

  fw_models = parse_hyundai_fw_models(hyundai_fingerprints)
  platform_models = parse_hyundai_platform_models(hyundai_values)
  routes_by_model = parse_hyundai_test_routes(routes_py)

  casper_candidates = [m for m in platform_models if "CASPER" in m]
  casper_fw = [m for m in fw_models if "CASPER" in m]
  casper_routes = {m: routes_by_model[m] for m in routes_by_model if "CASPER" in m}

  payload = {
    "generated_at_utc": datetime.now(timezone.utc).isoformat(),
    "source_root": str(root),
    "files": {
      "hyundai_fingerprints": str(hyundai_fingerprints),
      "hyundai_values": str(hyundai_values),
      "routes": str(routes_py),
    },
    "counts": {
      "platform_models_total": len(platform_models),
      "fw_models_total": len(fw_models),
      "route_models_total": len(routes_by_model),
    },
    "platform_models": platform_models,
    "fw_models": fw_models,
    "routes_by_model": routes_by_model,
    "casper_summary": {
      "platform_models": casper_candidates,
      "fw_models": casper_fw,
      "routes_by_model": casper_routes,
      "casper_ev_in_platform_models": "HYUNDAI_CASPER_EV" in platform_models,
      "casper_ev_in_fw_models": "HYUNDAI_CASPER_EV" in fw_models,
      "casper_ev_has_test_route": "HYUNDAI_CASPER_EV" in routes_by_model,
    },
  }

  out_path = Path(args.output)
  out_path.parent.mkdir(parents=True, exist_ok=True)
  out_path.write_text(json.dumps(payload, ensure_ascii=True, indent=2), encoding="utf-8")
  print(f"Wrote snapshot: {out_path}")
  return 0


if __name__ == "__main__":
  raise SystemExit(main())

