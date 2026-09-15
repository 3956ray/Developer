# Untrusted Code Static Scan

- Target: `/Users/orderly_ray/Projects/thinkV2`
- Verdict: **manual_review**
- Risk score: **8/100**
- Highest severity: **medium**
- Verdict basis: contextual or medium-severity indicators require human review

A low finding count means only that configured rules found no high-risk indicator. It is not a safety guarantee.

## Summary

| Severity | Count |
|---|---:|
| critical | 0 |
| high | 0 |
| medium | 1 |
| low | 0 |
| info | 0 |

## Findings

| Severity | Confidence | Context | Reachable | Rule | Path | Line | Evidence | Rationale |
|---|---|---|---|---|---|---:|---|---|
| medium | Confirmed | source_code | no | DISTRIBUTED-GIT-CONFIG | `.git/config` | - | .git/config is present | A downloaded full Git directory can contain local execution settings not delivered by a normal clone. |

## Context summary

```json
{
  "source_code": 1
}
```

## Score breakdown

```json
[
  {
    "rule_id": "DISTRIBUTED-GIT-CONFIG",
    "path": ".git/config",
    "severity": "medium",
    "source_kind": "source_code",
    "points": 8
  }
]
```

## Scan statistics

```json
{
  "candidates": 68,
  "text_files": 34,
  "binary_files": 34,
  "skipped_large": 0,
  "skipped_limit": 0,
  "unreadable": 0,
  "archives": 0
}
```

## Limitations

- Static inspection cannot prove that an artifact is safe.
- Runtime-fetched, encrypted, obfuscated, oversized, or generated payloads may not be visible.
- A network/upload capability does not prove successful data transfer.
- Repository reputation and dependency vulnerability lookups are outside the offline V1.1 scan.
- Binary metadata, code-signing, and package provenance checks are reserved for a later macOS/Python extension.
