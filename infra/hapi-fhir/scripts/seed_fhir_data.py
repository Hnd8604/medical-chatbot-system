#!/usr/bin/env python
"""Seed local HAPI FHIR with synthetic resources through the FHIR REST API."""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path


FHIR_BASE_URL = os.getenv("FHIR_BASE_URL", "http://localhost:8080/fhir").rstrip("/")
CHATBOT_BASE_URL = os.getenv("CHATBOT_BASE_URL", "http://localhost:8000").rstrip("/")
SEED_DIR = Path(__file__).resolve().parents[1] / "seed"


def patient_ids_in_bundle(bundle: bytes) -> list[str]:
    """Collect Patient IDs from a seed transaction bundle."""
    try:
        parsed = json.loads(bundle.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return []
    ids: list[str] = []
    for entry in parsed.get("entry", []):
        resource = entry.get("resource", {})
        if resource.get("resourceType") == "Patient" and resource.get("id"):
            ids.append(resource["id"])
    return ids


def invalidate_chatbot_cache(patient_ids: list[str]) -> None:
    """Best-effort: clear semantic cache for re-seeded patients.

    Seed dùng transaction PUT nên re-seed GHI ĐÈ dữ liệu bệnh nhân cũ — câu trả
    lời đã cache trong chatbot-service có thể thành stale. Nếu chatbot-service
    không chạy thì bỏ qua (cache TTL ngắn vẫn tự hết hạn).
    """
    if not patient_ids:
        return
    invalidated = 0
    for patient_id in patient_ids:
        request = urllib.request.Request(
            f"{CHATBOT_BASE_URL}/cache/invalidate/{patient_id}",
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=5):
                invalidated += 1
        except urllib.error.HTTPError:
            continue  # service chạy nhưng lỗi cho patient này — thử patient kế tiếp
        except (urllib.error.URLError, OSError):
            print(
                f"Chatbot service not reachable at {CHATBOT_BASE_URL}; "
                "skipping semantic cache invalidation (short TTL will expire stale entries)."
            )
            return
    print(f"Invalidated chatbot semantic cache for {invalidated} patients via {CHATBOT_BASE_URL}")


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
            SEED_DIR / "synthetic-fhir-transaction-bundle.json",
        ]
        if not seed_files:
            print(f"No seed JSON files found in: {SEED_DIR}", file=sys.stderr)
            return 1

        total_entries = 0
        seeded_patient_ids: list[str] = []
        for seed_file in seed_files:
            bundle = seed_file.read_bytes()
            response = post_bundle(bundle)
            seeded_patient_ids.extend(patient_ids_in_bundle(bundle))

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

    print(f"Seeded {total_entries} FHIR resources through {FHIR_BASE_URL}")
    invalidate_chatbot_cache(seeded_patient_ids)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
