#!/usr/bin/env python
"""Seed local HAPI FHIR with demo resources through the FHIR REST API."""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path


FHIR_BASE_URL = os.getenv("FHIR_BASE_URL", "http://localhost:8080/fhir").rstrip("/")
SEED_DIR = Path(__file__).resolve().parents[1] / "seed"


def post_bundle(bundle: bytes) -> dict:
    request = urllib.request.Request(
        FHIR_BASE_URL,
        data=bundle,
        method="POST",
        headers={
            "Accept": "application/fhir+json",
            "Content-Type": "application/fhir+json",
        },
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> int:
    try:
        seed_files = [
            SEED_DIR / "ambiguous-patient-demo-data-transaction-bundle.json",
            SEED_DIR / "demo-data-transaction-bundle.json",
            SEED_DIR / "extended-demo-data-transaction-bundle.json",
            SEED_DIR / "detailed-demo-data-transaction-bundle.json",
        ]
        if not seed_files:
            print(f"No seed JSON files found in: {SEED_DIR}", file=sys.stderr)
            return 1

        total_entries = 0
        for seed_file in seed_files:
            bundle = seed_file.read_bytes()
            response = post_bundle(bundle)

            if response.get("resourceType") != "Bundle":
                print(
                    f"Unexpected seed response for {seed_file.name}: "
                    f"resourceType={response.get('resourceType')!r}",
                    file=sys.stderr,
                )
                return 1

            entries = response.get("entry", [])
            statuses = [
                entry.get("response", {}).get("status", "unknown")
                for entry in entries
            ]
            total_entries += len(entries)
            print(f"Seeded {seed_file.name} through {FHIR_BASE_URL}")
            print(f"Transaction responses: {', '.join(statuses)}")
    except FileNotFoundError:
        print(f"Seed directory not found: {SEED_DIR}", file=sys.stderr)
        return 1
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        print(f"FHIR seed request failed with HTTP {exc.code}: {detail}", file=sys.stderr)
        return 1
    except urllib.error.URLError as exc:
        print(f"FHIR server is not reachable at {FHIR_BASE_URL}: {exc}", file=sys.stderr)
        return 1

    print(f"Seeded {total_entries} demo FHIR resources through {FHIR_BASE_URL}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
