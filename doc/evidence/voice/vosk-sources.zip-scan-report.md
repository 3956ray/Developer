# Untrusted Code Static Scan

- Target: `/private/tmp/thinkv2-voice-review/vosk-sources.zip`
- Verdict: **low_indicators**
- Risk score: **0/100**
- Highest severity: **info**
- Verdict basis: no configured high-risk indicator found
- Artifact SHA256: `e4c65b8b5bc6eda351c5fb29cfa4249f29bc9e8b5e346d168185815f59954446`

A low finding count means only that configured rules found no high-risk indicator. It is not a safety guarantee.

## Summary

| Severity | Count |
|---|---:|
| critical | 0 |
| high | 0 |
| medium | 0 |
| low | 0 |
| info | 0 |

## Findings

| Severity | Confidence | Context | Reachable | Rule | Path | Line | Evidence | Rationale |
|---|---|---|---|---|---|---:|---|---|
| info | Not Confirmed | - | - | NONE | - | - | No configured indicator found | Continue normal controls |

## Context summary

```json
{}
```

## Score breakdown

```json
[]
```

## Scan statistics

```json
{
  "candidates": 11,
  "text_files": 10,
  "binary_files": 1,
  "skipped_large": 0,
  "skipped_limit": 0,
  "unreadable": 0,
  "archives": 1
}
```

## Limitations

- Static inspection cannot prove that an artifact is safe.
- Runtime-fetched, encrypted, obfuscated, oversized, or generated payloads may not be visible.
- A network/upload capability does not prove successful data transfer.
- Repository reputation and dependency vulnerability lookups are outside the offline V1.1 scan.
- Binary metadata, code-signing, and package provenance checks are reserved for a later macOS/Python extension.
