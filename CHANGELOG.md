# Changelog

## Unreleased — experimental release candidate

Source versions: Companion **1.0.85+86**, Android Bridge **0.2.427 / 627**,
shared Rust crates **0.1.1**. No public stable release is declared.

- Share the Flutter connection UI across Android, Linux, Windows and macOS.
- Linux: pairing, activation and saved-pair reconnect verified on Mint 22.3 / Intel AX211.
- Windows: USB HCI pairing, activation and encrypted reconnect verified on Windows 10 / Realtek `0bda:b00e`; user-scoped DPAPI storage.
- Discover/select the Windows controller instead of using a personal PnP ID;
  refresh USB availability after hotplug and bind transport to the chosen identity.
- Stage the complete Windows native/protocol/Java bundle before replacing it.
- Show backend-declared feature availability and hide unsupported Watch service sections.
- Replace activation captures with deterministic synthetic serializer fixtures;
  remove personal device/network identifiers and repository-local AI tooling.
- Refresh English/Russian README and release instructions for all four platforms.

macOS pairing/IDS, desktop Watch application services, complete Health and
Watch.app parity remain incomplete. Distribution signing, CI, package audits
for Windows/macOS and candidate hardware/upgrade acceptance are still required.
See [build and release](docs/BUILD_AND_RELEASE.md) for the support matrix.
