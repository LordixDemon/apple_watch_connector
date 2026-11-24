# Experimental Legacy Apple Watch Pairing (`NRExtensiblePair`, Strategies 1–3)

> **Status: EXPERIMENTAL / STATIC FIRMWARE-DERIVED / UNVERIFIED ON PHYSICAL WATCH HARDWARE**
>
> All wire constants, UUIDs, state transitions, and framing formats below were reverse-engineered
> statically from Apple's shipped iOS 26.6 (`23G71`) daemons and validated only against synthetic
> unit and fake-controller tests. Behavior against physical legacy Apple Watch hardware
> (watchOS 1–9, e.g. Series 0–7) has **not** been tested yet and is **disabled by default**.

---

## 1. Pairing Strategy Matrix

From iOS 26.6 `nanoregistryd` (`NRPairingStrategy` table at `0x100187948`, helper `0x100057cdc`):

| Value | Name | Typical watchOS | Transport Sequence |
|---:|---|---|---|
| `1` | `NRPairingStrategySimultaneous` | watchOS 1–3 | No GATT `NANO` write; BLE SMP + `IDSBTLink` (`com.apple.ids`) simultaneously |
| `2` | `NRPairingStrategySequential` | watchOS 3–5 | GATT `NANO` write (`9AA4730F` / `5F6C6A23`) -> BLE SMP -> `IDSBTLink` (`com.apple.ids`) |
| `3` | `NRPairingStrategySequentialDirectIPSec` | watchOS 6–9 (e.g. Series 3 on 8.8.1) | GATT `NANO` write -> BLE SMP -> Direct IPSec (`com.apple.terminusLink` when OOBK / IDS keys) or `com.apple.ids` fallback |
| `4` | `NRPairingStrategyNetworkRelayPairing` | watchOS 9/10+ | **Production default** (verified on Watch7,5 / Ultra 2, watchOS 26.x / 27.x) |

---

## 2. Firmware Provenance & Wire Specification

1. **BLE Advertisements (`NanoRegistry` `_NRAdvertisingInfoFromPayload` @ `0x1e4abd548`):**
   - Primary 128-bit GATT Service UUID (AD Type `0x06`/`0x07`):
     - `9AA4730F-B25C-4CC3-B821-C931559FC196` (LE wire: `96 C1 9F 55 31 C9 21 B8 C3 4C 5C B2 0F 73 A4 9A`)
   - Local Name (AD Type `0x09`/`0x08`):
     - 8-char human-readable format `<5digits><strategyChar><materialChar><sizeChar>` (`'B'`=1, `'C'`=2, `'D'`=3; `'E'`=4 is modern Strategy 4 and excluded from legacy matching)
     - Manual format `"Apple Watch <5digits>"`
   - Service Data `0xFE25` subtype `0x06` with `identifier.pairingStrategy` in `{1, 2, 3}`.

2. **GATT Control Handshake (`nanoregistryd` `NRExtensiblePair::pairWithDevice:` @ `0x1000c6750`):**
   - Service: `9AA4730F-B25C-4CC3-B821-C931559FC196`
   - Characteristic: `5F6C6A23-8AC8-400E-810B-017134943460` (unencrypted Write Request `0x12`)
   - 6-byte payload: `'N' 'A' 'N' 'O'` + `strategy (1..3)` + `style` (`0x01` = OOB, `0x02` = Passcode)

3. **BLE SMP Bonding (`CID 0x0006`):**
   - **LE Secure Connections (`SC = 1`, watchOS 3–9 / BLE 4.2+):** P-256 ECDH + 20-round bit-by-bit Passkey Entry `f4` commitment (or OOB randomizers) + `f5` + `f6` + `HCI_LE_Start_Encryption` + Key Distribution.
   - **LE Legacy Pairing (`SC = 0`, BLE 4.0/4.1 fallback):** `c1`/`s1` TK commitment + `STK` encryption + distributed `0x06` LTK / `0x07` EDIV+Rand / `0x08` IRK / `0x09` Identity Address.

4. **Post-SMP `BT_CL` Handoff (`terminusd` & `identityservicesd`):**
   - `com.apple.terminusLink` (`0x0002`): runs `NrLinkBluetoothPrelude` negotiation (`PAIR_WITH_OOB_KEY (10)` or `PAIR_WITH_OOB_KEY_OR_IDS_KEYS (12)`).
   - `com.apple.ids` (`0x0003`, `IDSBTLink`): 1-byte (`<= 127`) / 2-byte (`0x8000 | len`) big-endian length-prefixed frames (`0x1006f550c` / `0x1006f5ec8`).

---

## 3. How to Enable (Strictly Opt-In)

Modern Strategy 4 behavior is untouched unless one of these explicit opt-ins is supplied:

- **Android HAL CLI:** pass `--experimental-legacy-pairing` alongside `--acknowledge-target CPH2653`.
- **Linux / Windows host CLI:** `... pair --experimental-legacy-pairing`.
- **Interactive stdin control:** send `EXPERIMENTAL_LEGACY_PAIRING_V1:1` before starting discovery (`EXPERIMENTAL_LEGACY_PAIRING_V1:0` disables it).
- When prompted (`LEGACY_PIN_PROMPT_V1:`), supply the 6-digit Watch passcode via `PIN:XXXXXX`.

---

## 4. Extension Points for Physical Hardware Contributors

If you have a physical Apple Watch on watchOS 1–9 (e.g. Series 3 on 8.8.1, Series 4–7 on 6–9):

1. **Legacy OOBK DirectIPSec (`terminusd` `0x100088140`, `0x100090ac0`, `0x1001e97be`):**
   - Implement IKEv2 PSK (`com.apple.networkrelay.companionlink.pairing.oobk`) and exchange legacy key notifies `0xC4EB` (Class D), `0xC4EC` (Class C), `0xC4AF` (Identity), or `0xC4E1` (IDS keys) inside `LegacyPairingRunner` after `NrLinkBluetoothPrelude` reconciles state `10`/`11`/`12`, then hand off to `NormalLinkPipeSession`.
2. **`IDSBTLink` Control Channel (`identityservicesd`):**
   - After `BT_CL ACCEPT com.apple.ids (0x0003)`, bridge `IDSBTLinkFrame` payloads in `LegacyPairingRunner.collectIdsBtLinkFrames` to `IdsControlChannelCodec` (`UTunControlChannel` `Hello` + `SetupChannel` + `Alloy`).
3. **Strategy 1 / Early watchOS GATT edge cases:**
   - Capture live `protocol.log` and HCI snoop traces on Strategy 2/3 watches if the Watch uses alternative ATT MTU / notification subscriptions on `9AA4730F` before SMP `Security Request`.
