# Untrusted Code Static Scan

- Target: `/private/tmp/thinkv2-voice-review/jna-sources.zip`
- Verdict: **sandbox_only**
- Risk score: **60/100**
- Highest severity: **high**
- Verdict basis: high-severity capability requires isolated review before execution
- Artifact SHA256: `0b9224e215b3c6a464959e3f994ddd64c14d46fb4014facd6afa1cc18e469466`

A low finding count means only that configured rules found no high-risk indicator. It is not a safety guarantee.

## Summary

| Severity | Count |
|---|---:|
| critical | 0 |
| high | 4 |
| medium | 0 |
| low | 0 |
| info | 0 |

## Findings

| Severity | Confidence | Context | Reachable | Rule | Path | Line | Evidence | Rationale |
|---|---|---|---|---|---|---:|---|---|
| high | Confirmed | source_code | no | DYNAMIC-CODE-EXEC | `com/sun/jna/CallbackReference.java` | 716 | this.function = new Function(address, callingConvention, (String) options.get(Library.OPTION_STRING_ENCODING)); | Uses dynamic evaluation or child-process execution. |
| high | Confirmed | source_code | no | DYNAMIC-CODE-EXEC | `com/sun/jna/Function.java` | 204 | return new Function(p, callFlags, encoding); | Uses dynamic evaluation or child-process execution. |
| high | Confirmed | source_code | no | DYNAMIC-CODE-EXEC | `com/sun/jna/NativeLibrary.java` | 150 | Function f = new Function(this, "GetLastError", Function.ALT_CONVENTION, encoding) { | Uses dynamic evaluation or child-process execution. |
| high | Confirmed | source_code | no | DYNAMIC-CODE-EXEC | `com/sun/jna/NativeLibrary.java` | 618 | function = new Function(this, functionName, callFlags, encoding); | Uses dynamic evaluation or child-process execution. |

## Context summary

```json
{
  "source_code": 4
}
```

## Score breakdown

```json
[
  {
    "rule_id": "DYNAMIC-CODE-EXEC",
    "path": "com/sun/jna/CallbackReference.java",
    "severity": "high",
    "source_kind": "source_code",
    "points": 20
  },
  {
    "rule_id": "DYNAMIC-CODE-EXEC",
    "path": "com/sun/jna/Function.java",
    "severity": "high",
    "source_kind": "source_code",
    "points": 20
  },
  {
    "rule_id": "DYNAMIC-CODE-EXEC",
    "path": "com/sun/jna/NativeLibrary.java",
    "severity": "high",
    "source_kind": "source_code",
    "points": 20
  }
]
```

## Scan statistics

```json
{
  "candidates": 70,
  "text_files": 70,
  "binary_files": 0,
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
