# CP3 dependency gate

No added, downloaded or executed third-party package. package.json/.nvmrc unchanged from accepted CP2. Runtime verified: Node v24.18.0, built-in SQLite 3.53.1, OpenSSL 3.5.7. crypto, sqlite, http, child_process, node:test, Intl are bundled Node capabilities; native client uses platform APIs and project-local JS. Prior CP0/CP1 origin/license evidence remains applicable, no new supplier/license boundary. This is a no-new-dependency check, not a fresh ecosystem CVE audit.

Receipt SHA256 is project-local ASCII-only code for the 64-character hex receipt, checked against node:crypto standard vectors and random inputs; never used as a general Unicode hash library. Pairing randomness/encryption use Node crypto; native receipt randomness uses wx.getRandomValues without weak fallback. Actual WeChat API/device compatibility remains NOT_RUN.
