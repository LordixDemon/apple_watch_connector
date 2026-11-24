# Standalone Research Probes & Reverse-Engineering Tools

This directory contains standalone analysis scripts, watchOS Simulator probes, benchmarks, and experimental host/device probes separated from production build, release, and test tooling in [`tools/`](../).

Contents:
- `probe_native_*.m`, `render_native_face_previews.m`, `native_face_layer_inspection.h` — Objective-C watchOS Simulator introspection & layer/compositor probes
- `probe_native_replicator_*.swift`, `verify_native_replicator_handshake.swift`, `native_replicator_data_bridge.S` — Swift/ASM Replicator & OPACK probes
- `compare_a2s_caches.go`, `BenchmarkClockFaceReceiver.java`, `benchmark_native_face_observations.dart` — standalone format & observation benchmarks
- `build_uuidtext_from_macho.py`, `capture_bridge_events.py`, `collect_native_face_profile.py`, `inspect_sysdiagnose_prefix.py`, `read_healthkit_type_definitions.py` — offline firmware/sysdiagnose/HealthKit extractors
- `linux_core_probe.py`, `windows_core_probe.py` — desktop core diagnostic probes
- `oneplus_watch_link_probe.rs` — standalone Rust USB/BLE probe (`cargo check --bin oneplus_watch_link_probe`)
