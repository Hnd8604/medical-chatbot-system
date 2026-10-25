"""Đo tỉ lệ hit đúng / hit nhầm của semantic cache và plan cache bằng bộ cặp câu.

Không cần stack đang chạy, không gọi LLM: chỉ dùng đúng model embedding của service
(``CACHE_EMBEDDING_MODEL``, fastembed tải về lần đầu) để tính độ giống cosine của từng
cặp, rồi so với ngưỡng.

- **Semantic cache** chỉ so trong cùng user + cùng bệnh nhân (cặp ``same_patient=false``
  không bao giờ được so). Hit nhầm = trả câu trả lời của câu kia. Báo hai dòng: chỉ theo
  ngưỡng (``semantic_raw``), và theo ngưỡng + chữ ký tất định ``nlp/query_signature.py``
  như service đang làm (``semantic``).
- **Plan cache** dùng chung mọi user. Ngoài ngưỡng còn có chốt tất định: chữ ký "khung"
  (``plan_signature``) phải trùng, và ``refill_identifiers`` điền lại khoảng ngày / ngưỡng
  từ câu mới. Script báo cả tỉ lệ hit nhầm trước và sau chốt; plan của câu thứ nhất được
  mô phỏng bằng rule planner.

```powershell
cd chatbot-service
python -m tests.eval.run_cache_pairs              # bảng theo ngưỡng + danh sách hit nhầm
python -m tests.eval.run_cache_pairs --json pairs.json
```
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any


PAIRS_PATH = Path(__file__).with_name("cache_pairs.jsonl")
THRESHOLDS = (0.90, 0.92, 0.94, 0.95, 0.96, 0.97, 0.98)


def load_pairs(path: Path = PAIRS_PATH) -> list[dict[str, Any]]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("//")
    ]


def cosine(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    norm = math.sqrt(sum(x * x for x in a)) * math.sqrt(sum(y * y for y in b))
    return dot / norm if norm else 0.0


def embed_all(texts: list[str], model_name: str) -> dict[str, list[float]]:
    from fastembed import TextEmbedding

    model = TextEmbedding(model_name=model_name)
    unique = sorted(set(texts))
    return {text: vector.tolist() for text, vector in zip(unique, model.embed(unique))}


def plan_guard_allows(question_a: str, question_b: str) -> bool | None:
    """Chốt tất định của plan cache có cho dùng plan của câu A cho câu B không.

    None = rule planner không lập được plan cho câu A (không mô phỏng được chốt).
    """
    from agent.planning.plan_cache import _plan_from_payload, _redact_step, refill_identifiers
    from agent.planning.rule_planner import rule_plan

    plan = rule_plan(question_a, provided_patient_id="BN-EVAL")
    if plan is None:
        return None
    cached = _plan_from_payload(
        {"route": "fhir", "answer_mode": plan.answer_mode, "steps": [_redact_step(step) for step in plan.steps]}
    )
    if cached is None:
        return None
    context = {"active_patient_id": "BN-EVAL"}
    return refill_identifiers(cached, question=question_b, conversation_context=context) is not None


def evaluate(pairs: list[dict[str, Any]], *, semantic_threshold: float, plan_threshold: float, model_name: str) -> dict[str, Any]:
    from cache.semantic_cache import embedding_text
    from nlp.query_signature import plan_signature, query_signature

    texts = [pair[key] for pair in pairs for key in ("a", "b")]
    vectors = embed_all(texts + [embedding_text(text) for text in texts], model_name)

    rows = []
    for pair in pairs:
        raw = cosine(vectors[pair["a"]], vectors[pair["b"]])
        semantic = cosine(vectors[embedding_text(pair["a"])], vectors[embedding_text(pair["b"])])
        plan = semantic  # hai cache dùng chung embedding_text
        rows.append(
            {
                **pair,
                "semantic_raw_score": round(raw, 4),
                "semantic_score": round(semantic, 4),
                "plan_score": round(plan, 4),
                "signature_match": query_signature(pair["a"]) == query_signature(pair["b"]),
                "plan_signature_match": plan_signature(pair["a"]) == plan_signature(pair["b"]),
                "plan_guard_allows": plan_guard_allows(pair["a"], pair["b"]),
            }
        )

    def rates(score_key: str, label_key: str, threshold: float, *, rows_subset, guard: str | None = None):
        should = [r for r in rows_subset if r[label_key]]
        should_not = [r for r in rows_subset if not r[label_key]]

        def hit(r):
            if r[score_key] < threshold:
                return False
            if guard == "plan":
                return r["plan_signature_match"] and r["plan_guard_allows"] is not False
            if guard == "signature":
                return r["signature_match"]
            return True

        return {
            "true_hit_rate": _pct(sum(hit(r) for r in should), len(should)),
            "false_hit_rate": _pct(sum(hit(r) for r in should_not), len(should_not)),
            "false_hits": [r["id"] for r in should_not if hit(r)],
        }

    semantic_rows = [r for r in rows if r.get("same_patient", True)]
    def all_rates(semantic_t: float, plan_t: float) -> dict[str, Any]:
        return {
            "semantic_raw": rates("semantic_raw_score", "same_answer", semantic_t, rows_subset=semantic_rows),
            "semantic": rates("semantic_score", "same_answer", semantic_t, rows_subset=semantic_rows, guard="signature"),
            "plan_raw": rates("plan_score", "same_plan", plan_t, rows_subset=rows),
            "plan_guarded": rates("plan_score", "same_plan", plan_t, rows_subset=rows, guard="plan"),
        }

    sweep = {f"{t:.2f}": all_rates(t, t) for t in THRESHOLDS}
    return {
        "pairs": len(pairs),
        "current": {
            "semantic_threshold": semantic_threshold,
            "plan_threshold": plan_threshold,
            **all_rates(semantic_threshold, plan_threshold),
        },
        "sweep": sweep,
        "rows": rows,
    }


def _pct(numerator: int, denominator: int) -> str:
    if not denominator:
        return "n/a"
    return f"{100 * numerator / denominator:.1f}% ({numerator}/{denominator})"


def print_result(result: dict[str, Any], *, verbose: bool) -> None:
    current = result["current"]
    print(f"\n=== {result['pairs']} cặp câu — ngưỡng hiện tại: semantic {current['semantic_threshold']}, plan {current['plan_threshold']} ===")
    names = ("semantic_raw", "semantic", "plan_raw", "plan_guarded")
    for name in names:
        data = current[name]
        print(f"{name:<14} hit đúng {data['true_hit_rate']:<16} hit nhầm {data['false_hit_rate']:<16} {', '.join(data['false_hits'])}")

    print("\n--- quét ngưỡng (hit đúng / hit nhầm) ---")
    print(f"{'ngưỡng':<8}" + "".join(f"{name:<32}" for name in names))
    for threshold, data in result["sweep"].items():
        cells = [f"{data[k]['true_hit_rate']} / {data[k]['false_hit_rate']}" for k in names]
        print(f"{threshold:<8}" + "".join(f"{cell:<32}" for cell in cells))

    if verbose:
        print("\n--- từng cặp ---")
        for row in sorted(result["rows"], key=lambda r: -r["semantic_score"]):
            label = "same" if row["same_answer"] else "diff"
            print(
                f"{row['id']:<9}{row['kind']:<18}{label:<6}raw={row['semantic_raw_score']:.3f} "
                f"sem={row['semantic_score']:.3f} sig={'=' if row['signature_match'] else '≠'} "
                f"plan={row['plan_score']:.3f} guard={row['plan_guard_allows']!s:<6} {row['a']} | {row['b']}"
            )


def main() -> None:
    try:
        import sys

        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    from app.config import get_settings

    settings = get_settings()
    parser = argparse.ArgumentParser(description="Đo hit đúng / hit nhầm của semantic cache và plan cache.")
    parser.add_argument("--pairs", type=Path, default=PAIRS_PATH)
    parser.add_argument("--json", type=Path, help="Ghi kết quả chi tiết ra file JSON.")
    parser.add_argument("--verbose", action="store_true", help="In điểm của từng cặp.")
    args = parser.parse_args()

    result = evaluate(
        load_pairs(args.pairs),
        semantic_threshold=settings.cache_similarity_threshold,
        plan_threshold=settings.plan_cache_similarity_threshold,
        model_name=settings.cache_embedding_model,
    )
    print_result(result, verbose=args.verbose)
    if args.json:
        args.json.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
