# Untrusted Code Static Scan

- Target: `/private/tmp/thinkv2-voice-002-source-review`
- Verdict: **sandbox_only**
- Risk score: **23/100**
- Highest severity: **high**
- Verdict basis: high-severity capability requires isolated review before execution

A low finding count means only that configured rules found no high-risk indicator. It is not a safety guarantee.

## Summary

| Severity | Count |
|---|---:|
| critical | 0 |
| high | 3 |
| medium | 0 |
| low | 3 |
| info | 0 |

## Findings

| Severity | Confidence | Context | Reachable | Rule | Path | Line | Evidence | Rationale |
|---|---|---|---|---|---|---:|---|---|
| high | Confirmed | source_code | no | DESTRUCTIVE-CLEANUP | `android.yaml` | 68 | rm -rf ./build-android-arm64-v8a/ | Deletes files or directories, potentially to remove evidence. |
| high | Confirmed | source_code | no | DESTRUCTIVE-CLEANUP | `android.yaml` | 85 | rm -rf ./build-android-armv7-eabi | Deletes files or directories, potentially to remove evidence. |
| high | Confirmed | source_code | no | DESTRUCTIVE-CLEANUP | `android.yaml` | 102 | rm -rf ./build-android-x86-64 | Deletes files or directories, potentially to remove evidence. |
| low | Confirmed | source_code | no | TEMP-STAGING | `android.yaml` | 179 | repo_token: ${{ secrets.UPLOAD_GH_SHERPA_ONNX_TOKEN }} | Creates or references a temporary staging location. |
| low | Confirmed | source_code | no | TEMP-STAGING | `android.yaml` | 226 | path: /tmp/jniLibs | Creates or references a temporary staging location. |
| low | Confirmed | source_code | no | TEMP-STAGING | `android.yaml` | 231 | ls -lh /tmp/jniLibs | Creates or references a temporary staging location. |

## Context summary

```json
{
  "source_code": 6
}
```

## Score breakdown

```json
[
  {
    "rule_id": "DESTRUCTIVE-CLEANUP",
    "path": "android.yaml",
    "severity": "high",
    "source_kind": "source_code",
    "points": 20
  },
  {
    "rule_id": "TEMP-STAGING",
    "path": "android.yaml",
    "severity": "low",
    "source_kind": "source_code",
    "points": 3
  }
]
```

## Scan statistics

```json
{
  "candidates": 15,
  "text_files": 6,
  "binary_files": 9,
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
