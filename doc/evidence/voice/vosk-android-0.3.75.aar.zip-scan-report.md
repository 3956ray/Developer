# Untrusted Code Static Scan

- Target: `/private/tmp/thinkv2-voice-review/vosk-android-0.3.75.aar.zip`
- Verdict: **low_indicators**
- Risk score: **0/100**
- Highest severity: **info**
- Verdict basis: no configured high-risk indicator found
- Artifact SHA256: `ab2f8b91ac8051561aa325546b35fed9a68b36b8121bac5c6fb927525c4adfad`

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
  "candidates": 7,
  "text_files": 2,
  "binary_files": 1,
  "skipped_large": 4,
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
