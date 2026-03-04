#!/usr/bin/env python3
"""
Build Hyundai seed signatures from public openpilot routes.

This script is intended to run with openpilot's uv environment:
  - capnp available
  - requests available
"""

from __future__ import annotations

import argparse
import bz2
import shutil
import json
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

import capnp  # type: ignore
import requests
import zstandard as zstd  # type: ignore


IGNORE_ADDRS = {0x7DF, 0x7E0, 0x7E8}
FILE_CANDIDATES = ("rlog.bz2", "qlog.bz2", "rlog.zst", "qlog.zst")


@dataclass(frozen=True)
class CanSignature:
  address: int
  length: int


def route_url(route: str, segment: int, file_name: str) -> str:
  route_path = route.replace("|", "/")
  return f"https://commadataci.blob.core.windows.net/openpilotci/{route_path}/{segment}/{file_name}"


def route_segment_dir(route: str, segment: int, cache_root: Path) -> Path:
  route_path = route.replace("|", "/")
  return cache_root / route_path / str(segment)


def fetch_route_log(route: str, segment: int, cache_root: Path) -> tuple[bytes, str, Path]:
  route_path = route.replace("|", "/")
  seg_dir = route_segment_dir(route, segment, cache_root)
  seg_dir.mkdir(parents=True, exist_ok=True)
  missing_marker = seg_dir / ".missing"

  # Try cache first
  for file_name in FILE_CANDIDATES:
    cached = seg_dir / file_name
    if cached.exists():
      return cached.read_bytes(), file_name, seg_dir
  if missing_marker.exists():
    raise RuntimeError(f"No downloadable route log found: route={route}, segment={segment}")

  # Download first available candidate
  for file_name in FILE_CANDIDATES:
    url = route_url(route, segment, file_name)
    head = requests.head(url, timeout=8)
    if head.status_code != 200:
      continue
    resp = requests.get(url, timeout=90)
    if resp.status_code == 200 and resp.content:
      out = seg_dir / file_name
      out.write_bytes(resp.content)
      return resp.content, file_name, seg_dir

  missing_marker.write_text("missing", encoding="utf-8")
  raise RuntimeError(f"No downloadable route log found: route={route}, segment={segment}")


def decompress_log(data: bytes, file_name: str) -> bytes:
  if file_name.endswith(".bz2") or data.startswith(b"BZh"):
    return bz2.decompress(data)
  if file_name.endswith(".zst") or data.startswith(b"\x28\xB5\x2F\xFD"):
    dctx = zstd.ZstdDecompressor()
    return dctx.decompress(data)
  return data


def extract_signatures(
  schema,
  raw_log_data: bytes,
) -> tuple[set[CanSignature], Counter[CanSignature]]:
  sigs: set[CanSignature] = set()
  counts: Counter[CanSignature] = Counter()
  for event in schema.Event.read_multiple_bytes(raw_log_data):
    try:
      if event.which() != "can":
        continue
    except Exception:
      continue

    for c in event.can:
      if (c.src % 0x80) != 0:
        continue
      if c.address >= 0x800:
        continue
      if c.address in IGNORE_ADDRS:
        continue
      sig = CanSignature(address=int(c.address), length=len(c.dat))
      sigs.add(sig)
      counts[sig] += 1
  return sigs, counts


def extract_route_signatures(
  route: str,
  cache_root: Path,
  schema,
  max_segment_scan: int,
) -> tuple[int, set[CanSignature], Counter[CanSignature]]:
  last_err: Exception | None = None
  for segment in range(max_segment_scan + 1):
    try:
      seg_dir = route_segment_dir(route, segment, cache_root)
      parsed_cache = seg_dir / ".can_fingerprint.json"
      if parsed_cache.exists():
        cached = json.loads(parsed_cache.read_text(encoding="utf-8"))
        sigs = {
          CanSignature(address=int(s["address"]), length=int(s["length"]))
          for s in cached.get("signatures", [])
        }
        counts = Counter({
          CanSignature(address=int(k.split(":")[0]), length=int(k.split(":")[1])): int(v)
          for k, v in cached.get("counts", {}).items()
        })
        if sigs:
          return segment, sigs, counts

      blob, fname, seg_dir = fetch_route_log(route=route, segment=segment, cache_root=cache_root)
      decompressed = decompress_log(blob, fname)
      sig_set, sig_count = extract_signatures(schema, decompressed)
      if sig_set:
        cache_payload = {
          "signatures": [{"address": s.address, "length": s.length} for s in sorted(sig_set, key=lambda x: (x.address, x.length))],
          "counts": {f"{s.address}:{s.length}": c for s, c in sig_count.items()},
        }
        parsed_cache.write_text(json.dumps(cache_payload, ensure_ascii=True, indent=2), encoding="utf-8")
      if sig_set:
        return segment, sig_set, sig_count
    except Exception as e:  # noqa: BLE001
      last_err = e
      continue

  if last_err is not None:
    raise RuntimeError(f"Could not extract route signatures: route={route}: {last_err}") from last_err
  raise RuntimeError(f"Could not extract route signatures: route={route}")


def stable_sort_sigs(sigs: Iterable[CanSignature], counts: Counter[CanSignature]) -> list[CanSignature]:
  return sorted(
    sigs,
    key=lambda s: (-counts.get(s, 0), s.address, s.length),
  )


def select_seed_signatures(
  model: str,
  common: set[CanSignature],
  all_common_by_model: dict[str, set[CanSignature]],
  counts: Counter[CanSignature],
  min_count: int = 8,
  max_count: int = 24,
) -> list[CanSignature]:
  others_union: set[CanSignature] = set()
  for other_model, other_common in all_common_by_model.items():
    if other_model == model:
      continue
    others_union |= other_common

  unique = common - others_union
  sorted_unique = stable_sort_sigs(unique, counts)
  selected: list[CanSignature] = list(sorted_unique[:max_count])

  if len(selected) < min_count:
    sorted_common = stable_sort_sigs(common, counts)
    for sig in sorted_common:
      if sig in selected:
        continue
      selected.append(sig)
      if len(selected) >= min_count:
        break

  return selected[:max_count]


def prepare_capnp_schema(openpilot_root: Path, cache_root: Path):
  schema_dir = cache_root / "schema"
  schema_dir.mkdir(parents=True, exist_ok=True)
  include_dir = schema_dir / "include"
  include_dir.mkdir(parents=True, exist_ok=True)
  cereal_dir = openpilot_root / "cereal"
  opendbc_car_dir = openpilot_root / "opendbc_repo" / "opendbc" / "car"

  required = {
    cereal_dir / "log.capnp": schema_dir / "log.capnp",
    cereal_dir / "legacy.capnp": schema_dir / "legacy.capnp",
    cereal_dir / "custom.capnp": schema_dir / "custom.capnp",
    cereal_dir / "include" / "c++.capnp": include_dir / "c++.capnp",
    opendbc_car_dir / "car.capnp": schema_dir / "car.capnp",
  }
  missing = [str(src) for src in required if not src.exists()]
  if missing:
    raise FileNotFoundError(f"Missing capnp source files: {missing}")

  for src, dst in required.items():
    if not dst.exists():
      shutil.copyfile(src, dst)

  return capnp.load(
    str(schema_dir / "log.capnp"),
    imports=[
      str(schema_dir),
      str(include_dir),
    ],
  )


def main() -> int:
  parser = argparse.ArgumentParser(description="Build Hyundai seed signatures from public openpilot routes")
  parser.add_argument(
    "--snapshot",
    default=r"e:\Carrotpilot_Galaxy\Carrotpilot_galaxy\offline_porting\data\hyundai_openpilot_snapshot.json",
  )
  parser.add_argument(
    "--openpilot-root",
    default=r"e:\Carrotpilot_Galaxy\Carrotpilot",
  )
  parser.add_argument(
    "--output",
    default=r"e:\Carrotpilot_Galaxy\Carrotpilot_galaxy\offline_porting\data\hyundai_seed_signatures.json",
  )
  parser.add_argument("--max-routes-per-model", type=int, default=2)
  parser.add_argument("--max-segment-scan", type=int, default=20)
  args = parser.parse_args()

  snapshot_path = Path(args.snapshot)
  snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
  routes_by_model: dict[str, list[str]] = snapshot["routes_by_model"]

  models = ["KIA_EV6", "HYUNDAI_IONIQ_5", "HYUNDAI_KONA_EV"]
  openpilot_root = Path(args.openpilot_root)
  cache_root = Path(args.output).parent.parent / "cache" / "routes"
  cache_root.mkdir(parents=True, exist_ok=True)
  schema = prepare_capnp_schema(openpilot_root, cache_root)

  route_sets: dict[str, list[set[CanSignature]]] = {}
  route_counts: dict[str, Counter[CanSignature]] = {}
  used_routes: dict[str, list[str]] = {}
  route_errors: dict[str, list[str]] = {}

  for model in models:
    routes = routes_by_model.get(model, [])
    if not routes:
      continue
    selected_routes = routes[: max(1, args.max_routes_per_model)]
    used_routes[model] = []
    route_errors[model] = []
    model_sets: list[set[CanSignature]] = []
    model_counter: Counter[CanSignature] = Counter()

    for route in selected_routes:
      try:
        used_segment, sig_set, sig_count = extract_route_signatures(
          route=route,
          cache_root=cache_root,
          schema=schema,
          max_segment_scan=args.max_segment_scan,
        )
        model_sets.append(sig_set)
        model_counter.update(sig_count)
        # Store route with concrete segment for reproducibility.
        used_routes[model].append(f"{route}/{used_segment}")
      except Exception as e:  # noqa: BLE001
        route_errors[model].append(f"{route}: {e}")
        continue

    if not model_sets:
      continue

    route_sets[model] = model_sets
    route_counts[model] = model_counter

  common_by_model: dict[str, set[CanSignature]] = {}
  for model, sets in route_sets.items():
    if not sets:
      continue
    common = set(sets[0])
    for sig_set in sets[1:]:
      common &= sig_set
    common_by_model[model] = common

  seeds_by_model: dict[str, list[dict[str, int]]] = {}
  metadata: dict[str, dict[str, object]] = {}
  for model, common in common_by_model.items():
    selected = select_seed_signatures(
      model=model,
      common=common,
      all_common_by_model=common_by_model,
      counts=route_counts[model],
    )
    seeds_by_model[model] = [{"address": s.address, "length": s.length} for s in selected]
    metadata[model] = {
      "source": "openpilotci_route",
      "routes_used": used_routes.get(model, []),
      "route_errors": route_errors.get(model, []),
      "signatures_common_count": len(common),
      "signatures_selected_count": len(selected),
    }

  # Temporary fallback: no FW_VERSIONS and no public route coverage for CASPER_EV in current snapshot.
  seeds_by_model["HYUNDAI_CASPER_EV"] = [
    {"address": 0x105, "length": 8},
    {"address": 0x1A0, "length": 8},
    {"address": 0x260, "length": 8},
    {"address": 0x329, "length": 8},
  ]
  metadata["HYUNDAI_CASPER_EV"] = {
    "source": "temporary_fallback",
    "reason": "no openpilot FW_VERSIONS and no public route in snapshot",
    "alias_candidate": "HYUNDAI_CASPER",
  }

  out = {
    "generated_at_utc": datetime.now(timezone.utc).isoformat(),
    "from_snapshot": str(snapshot_path),
    "models": list(seeds_by_model.keys()),
    "seeds_by_model": seeds_by_model,
    "metadata": metadata,
  }

  out_path = Path(args.output)
  out_path.parent.mkdir(parents=True, exist_ok=True)
  out_path.write_text(json.dumps(out, ensure_ascii=True, indent=2), encoding="utf-8")
  print(f"Wrote seeds: {out_path}")
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
