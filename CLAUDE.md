# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build commands
- Build: `./gradlew assembleDebug`
- Build release: `./gradlew assembleRelease`
- Lint: `./gradlew lint`
- Run tests: `./gradlew test` (no test suite currently exists)

## Architecture

CatConnect is a single-module Android app (package `moe.reimu.ancsreceiver`) that bridges notifications and media control between iOS/Android devices over BLE. It implements Apple's ANCS and AMS protocols.

**Two operating modes**, each in its own service:
- **Client** (`AncsService`): foreground service that connects as a BLE GATT client to a paired device. Receives ANCS notifications (forwarded as Android notifications) and AMS media info (shown as a media notification with playback controls). Target device address is persisted in `AppSettings`.
- **Server** (`ServerService`): `NotificationListenerService` that opens a BLE GATT server exposing ANCS characteristics. When Android notifications arrive, it notifies connected BLE clients. Filters out notifications that originated from the client side via `EXTRA_DEVICE_ADDRESS`.

**Key layers:**
- `ble/` — Coroutine-based BLE abstractions. `BleDevice` wraps GATT client operations using `CompletableDeferred` for async completion; use it with `use {}` blocks. `BleServer`/`BleServerService`/`BleServerCharacteristic` model the GATT server side.
- `ancs/` — ANCS protocol types: `NotificationEvent` (parse/encode), `NotificationAttributeRequest`, `AppAttributeRequest`, attribute constants.
- `ams/` — AMS protocol types: `AmsBleService` locates AMS characteristics, `EntityUpdateNotification` parses entity updates (player state, track metadata).
- `services/` — Android services: `AncsService` (client foreground service), `ServerService` (notification listener + GATT server), `ReceiverTileService` (Quick Settings tile).
- `utils/` — Notification channel setup, runtime permission helpers, internal broadcast registration, TLV buffer parsing.

**UI:** Single-activity Compose app (`MainActivity`). `MainViewModel` orchestrates device setup: BLE connect → discover services → subscribe to ANCS notification/data sources. The UI lists bonded devices and shows Bluetooth/permission status.

**Service communication:** Internal broadcasts with signature-level permission (`INTERNAL_BROADCASTS`). `ServiceState` defines action constants. `AncsService` broadcasts its running state; the UI and tile observe it.

**Signing:** Release builds read keystore config from `signing.properties` (not committed). The `keystore.jks` in the repo root is the debug keystore.
