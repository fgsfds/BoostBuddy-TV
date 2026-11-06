# TV fork modification notice

This source tree modifies BoostBuddy 1.16.0, upstream commit `b9acb6c2cde4f37907e70bf166a2f27c7d57c322`, to implement the requested Google TV client. Changes made on 2026-11-06 include:

- New Android TV sidebar, subscriptions directory, video grid, search and remote focus restoration.
- Native phone-code sign-in as the default, with international phone validation and a compact TV layout.
- Complete subscription pagination and safer feed refresh/pagination cancellation.
- Video-card metadata retention, playback eligibility states and consistent media request headers.
- Separate fork package, label, banner and build/signing configuration.
- Removal of the upstream analytics SDK, automatic update prompts and sensitive network/media diagnostics.
- Android unit tests, fork documentation, and a debug APK workflow for all branch pushes, pull requests, and manual runs.
- Actual emulator screenshots, with a debug-only offline fixture for the browsing captures.

The original source copyright notice remains in README.MD and docs/UPSTREAM_README.md; the Apache 2.0 license remains in LICENSE. No SmartTube source code or branding is included.
