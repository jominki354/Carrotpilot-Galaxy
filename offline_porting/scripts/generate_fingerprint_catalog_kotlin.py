#!/usr/bin/env python3
"""
Generate Kotlin fingerprint catalog source from offline seed JSON.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def q(text: str) -> str:
  return json.dumps(text, ensure_ascii=True)


def render_string_list(values: list[str], indent: str = "      ") -> list[str]:
  if not values:
    return ["emptyList()"]
  lines = ["listOf("]
  for v in values:
    lines.append(f"{indent}{q(v)},")
  lines.append("    )")
  return lines


def render_signatures_block(model: str, sigs: list[dict[str, int]]) -> list[str]:
  lines = [f'    {q(model)} to setOf(']
  for sig in sigs:
    addr = int(sig["address"])
    length = int(sig["length"])
    lines.append(f"      CanSignature(address = 0x{addr:X}, length = {length}),")
  lines.append("    ),")
  return lines


def render_metadata_block(model: str, metadata: dict[str, object]) -> list[str]:
  lines = [f'    {q(model)} to FingerprintProfileMetadata(']
  lines.append(f'      source = {q(str(metadata.get("source", "unknown")))},')

  note = metadata.get("note") or metadata.get("reason")
  if isinstance(note, str) and note:
    lines.append(f"      note = {q(note)},")

  alias_candidate = metadata.get("alias_candidate") or metadata.get("aliasCandidate")
  if isinstance(alias_candidate, str) and alias_candidate:
    lines.append(f"      aliasCandidate = {q(alias_candidate)},")

  route_refs = metadata.get("routes_used") or metadata.get("routeRefs") or []
  if isinstance(route_refs, list):
    rendered = render_string_list([str(v) for v in route_refs], indent="        ")
    if len(rendered) == 1:
      lines.append(f"      routeRefs = {rendered[0]},")
    else:
      lines.append("      routeRefs = " + rendered[0])
      for line in rendered[1:-1]:
        lines.append(line)
      lines.append(f"{rendered[-1]},")

  route_errors = metadata.get("route_errors") or metadata.get("routeErrors") or []
  if isinstance(route_errors, list) and route_errors:
    rendered = render_string_list([str(v) for v in route_errors], indent="        ")
    lines.append("      routeErrors = " + rendered[0])
    for line in rendered[1:-1]:
      lines.append(line)
    lines.append(f"{rendered[-1]},")

  lines.append("    ),")
  return lines


def main() -> int:
  parser = argparse.ArgumentParser(description="Generate FingerprintCatalogGenerated.kt from seed JSON")
  parser.add_argument(
    "--seed-json",
    default=r"e:\Carrotpilot_Galaxy\Carrotpilot_galaxy\offline_porting\data\hyundai_seed_signatures.json",
  )
  parser.add_argument(
    "--output",
    default=r"e:\Carrotpilot_Galaxy\Carrotpilot_galaxy\app\src\main\java\io\carrotpilot\galaxy\vehicle\FingerprintCatalogGenerated.kt",
  )
  args = parser.parse_args()

  seed_path = Path(args.seed_json)
  data = json.loads(seed_path.read_text(encoding="utf-8"))
  seeds_by_model: dict[str, list[dict[str, int]]] = data.get("seeds_by_model", {})
  metadata_by_model: dict[str, dict[str, object]] = data.get("metadata", {})
  models = sorted(seeds_by_model.keys())

  lines: list[str] = []
  lines.append("package io.carrotpilot.galaxy.vehicle")
  lines.append("")
  lines.append("// AUTO-GENERATED FILE. DO NOT EDIT MANUALLY.")
  lines.append(f"// Source: {seed_path.as_posix()}")
  generated_at = data.get("generated_at_utc", "unknown")
  lines.append(f"// Generated at UTC: {generated_at}")
  lines.append("object FingerprintCatalogGenerated {")
  lines.append("  val signaturesByCar: Map<String, Set<CanSignature>> = mapOf(")
  for model in models:
    lines.extend(render_signatures_block(model, seeds_by_model[model]))
  lines.append("  )")
  lines.append("")
  lines.append("  val metadataByCar: Map<String, FingerprintProfileMetadata> = mapOf(")
  for model in models:
    md = metadata_by_model.get(model, {})
    lines.extend(render_metadata_block(model, md))
  lines.append("  )")
  lines.append("}")
  lines.append("")

  out_path = Path(args.output)
  out_path.parent.mkdir(parents=True, exist_ok=True)
  out_path.write_text("\n".join(lines), encoding="utf-8")
  print(f"Wrote Kotlin catalog: {out_path}")
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
