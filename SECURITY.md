# Security model

This is a hobby-grade tool. The primitives are standard and used correctly at
the envelope level, but the project has had no third-party audit. Do not stake
anything you cannot afford to lose on it, and read the limits below before using
it where the stakes are high.

## What protects the data

All confidentiality rests on the user's code and nothing else:

- Files and the vault index are encrypted with keys derived from the code by
  PBKDF2-HMAC-SHA512 at 500,000 iterations, then AES-256-GCM followed by
  ChaCha20-Poly1305, with an outer HMAC-SHA512.
- There is no embedded key and no default key. A blank code leaves the vault
  locked. A build with no code, or a modified build, cannot decrypt anything.
- The master/duress verifier stored in app preferences is salted
  PBKDF2-HMAC-SHA512 with a random per-install salt, not a fast hash, so the
  preferences file is not a fast offline brute-force oracle. It does not protect
  the data either way; the data is encrypted independently with the code.

Because the code is the only secret, code entropy is the whole game. An 8-character
code from the 24-symbol keypad is about 37 bits; longer is much stronger. PBKDF2
slows guessing but does not replace a long code.

## Quantum posture

Everything protecting the data is symmetric (AES-256, ChaCha20) or hash-based
(PBKDF2-HMAC-SHA512). These face only Grover's algorithm, which leaves AES-256 at
about 128-bit post-quantum strength. There is no RSA or elliptic-curve anywhere
for Shor's algorithm to break, so the data at rest is already quantum-resistant
for this threat model.

## What it does NOT hide

- **Presence of blocks.** Chunks live in FLAC `APPLICATION` metadata blocks. The
  contents are encrypted, but anyone who parses the FLAC metadata can see that
  non-standard blocks exist. This is plausible deniability at the container
  level, not forensic invisibility.
- **Traces outside the app.** Uninstalling AlphaVault wipes its own app-private
  data (preferences, cache), and the vault itself lives in the FLAC files, not on
  the phone. But the operating system may retain install history and logs the app
  cannot touch. "No data on the phone" is true for the app's own storage; it is
  not a guarantee against a full forensic examination of the device.

## Hardening

- `FLAG_SECURE` on every screen blocks screenshots, screen recording, and the
  recents thumbnail. (A build made with `-PallowScreenshots` disables this for
  documentation; release builds do not.)
- Backups are disabled (`allowBackup=false`, data-extraction rules exclude
  everything). Cleartext network traffic is disabled.
- In-app viewers render decrypted bytes inside the secure window and never hand
  plaintext to another app.

## Multiple codes and one-time codes

A dataset can be encrypted under one random 256-bit data key that is wrapped
separately for each code allowed to open it (`KeyRing`). Each wrap is its own
cascade envelope, so a code reveals only whether it opens that dataset, not how
many other codes exist or how close a guess was. A wrap can be one-time: opening
with it removes the wrap, so the code opens nothing afterward. That is the
journalist model, a set of single-use codes handed out separately from the data.
The key ring holds no verifier; a code that matches nothing returns the same
answer an empty ring gives.

## Duress

A duress code, distinct from the master code and set at onboarding, erases the
stored credentials and strips every AlphaVault block from the carriers when
entered, then shows an empty vault. It cannot be undone.

## Reporting

This is a personal project without a formal disclosure process. Open an issue for
non-sensitive reports.
