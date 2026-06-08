# TrekMesh - Delay Tolerant Network P2P Application

## Overview

**TrekMesh** is an Android native application that implements a **Delay Tolerant Network (DTN)** architecture using **Google Nearby Connections API** to enable seamless peer-to-peer communication between devices in hiking, outdoor, or disconnected scenarios. The application creates a **mesh topology network** (M-to-N) allowing multiple devices to discover, connect, and exchange data without relying on internet connectivity or cellular networks.

The app is designed for scenarios where:
- Users are in remote locations with no cellular or Wi-Fi infrastructure
- Devices move in and out of range (delay-tolerant scenario)
- Multiple devices need to exchange telemetry, location data, or emergency information
- Battery efficiency and background operation are critical

### Transport selection (BLE vs Wi‑Fi Direct)

TrekMesh itself does not explicitly choose between Bluetooth Low Energy (BLE) and Wi‑Fi Direct. The underlying Google Nearby Connections API selects the transport automatically based on device capabilities and current radio state. In practice this means:

- If both devices support Wi‑Fi Direct and it is available, Nearby will often prefer Wi‑Fi Direct for higher bandwidth and longer range.
- If Wi‑Fi Direct is unavailable (or Wi‑Fi is turned off) Nearby will use BLE for low‑power, short‑range communication.
- Nearby may attempt multiple transports in parallel and choose the one that completes connection establishment first.

If you want to force a specific transport during testing, you can disable the other radio on the device (for example, turn off Wi‑Fi to force BLE, or turn off Bluetooth to force Wi‑Fi Direct).

---

## Key Features

### 1. **Automatic Peer Discovery & Connection**
- Devices automatically discover nearby endpoints using **Google Nearby Connections API**
- No manual pairing or user confirmation required (seamless UX)
- Simultaneous **Advertising** and **Discovery** in background
- Each device generates a cryptographically random ephemeral name (e.g., `Hiker-A3F9C102`) using `SecureRandom` — changes each session for privacy
- Duplicate connection deduplication: the same physical device discovered via multiple transports (BT + WiFi) is connected only once

### 2. **Mesh Topology (M-to-N)**
- Uses **Strategy.P2P_CLUSTER** for true mesh networking
- Each device can communicate with multiple peers simultaneously
- Enables data propagation across multiple hops
- Ideal for Delay Tolerant Networks where devices may not have continuous connectivity

### 3. **Store-and-Forward DTN Messaging**
- Messages are persisted in a local **Room database** and forwarded when peers become available
- Each message carries a **TTL (Time To Live)** counter (default: 7 hops), decremented at each relay
- On connection, the full buffer is flushed to the newly connected peer (store-and-forward)
- Message deduplication via `seenMessageIds` — IDs persist across app restarts
- Buffer capped at 200 messages; oldest entries pruned automatically
- Messages with TTL ≤ 0 are purged from the DB after flush

### 4. **AES-256-GCM Payload Encryption**
- All message payloads are encrypted with **AES-256-GCM** before transmission
- A fresh 12-byte IV is generated with `SecureRandom` for every message
- Pre-shared symmetric key (256-bit); all devices with the app share the same key
- Messages that fail decryption (wrong key, tampering) are silently discarded
- Wire format: `MSG|<id>|<sender>|<ttl>|<base64(IV + ciphertext)>`

### 5. **Delivery Acknowledgement (ACK)**
- When a message is received for the first time, the receiving device sends an **ACK** directly back to the sender
- ACK wire format: `ACK|<originalMsgId>|<receiverName>`
- Message status in the UI transitions from `⏳ PENDING` to `✓ DELIVERED` upon ACK receipt
- Status is persisted in the Room database
- ACKs are point-to-point (not mesh-flooded) and are sent only once per message

### 6. **Split Chat / System Log UI**
- **Chat area** (upper 2/3): shows only user messages with sender name and delivery status
- **System log** (lower 1/3): shows connection events, errors, and delivery confirmations for sent messages
- Both areas auto-scroll on new content
- Delivery confirmation (`Messaggio consegnato a Hiker-XXXX`) shown in log only for messages sent by this device, and only once per message

### 7. **Foreground Service with Boot Auto-Start**
- Runs as a **Foreground Service** with persistent notification
- Prevents Android Doze Mode from terminating the service
- Returns `START_STICKY` to auto-recover from out-of-memory kills
- `BootReceiver` restarts the service automatically after device reboot

---

## Technical Architecture

### Connectivity Types

#### **Bluetooth Low Energy (BLE)**
- **Range**: ~50-100 meters in open field, ~10-30 meters indoors
- **Power Consumption**: ~5-10 mA transmitting, <1 mA idle
- **Bandwidth**: ~125 Kbps
- **Android Requirement**: `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (Android 12+)

#### **Wi-Fi Direct (P2P)**
- **Range**: ~100-200 meters in open field
- **Power Consumption**: ~150-300 mA transmitting
- **Bandwidth**: ~11-54+ Mbps
- **Android Requirement**: `NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION` (Android 13+)

---

## Network Topology

### M-to-N Mesh Topology (P2P_CLUSTER Strategy)

```
        Device A (Hiker-A3F9C102)
           /         \
          /           \
    Device B        Device C
  (Hiker-00B1E4F2)  (Hiker-CC74A801)
         \            /
          \          /
           Device D (Hiker-5512DE90)
```

**Characteristics:**
- **Multiple connections per device**: Each device can maintain N simultaneous connections
- **No central server/hub**: Truly decentralized
- **Self-healing**: If one device goes offline, others remain connected
- **Flood routing**: Messages propagate through multiple hops, bounded by TTL

---

## Delay Tolerant Network (DTN) Design

TrekMesh implements DTN principles:

1. **Intermittent Connectivity**: Devices may be disconnected for extended periods and reconnect dynamically
2. **Store-and-Forward**: Messages are buffered locally and delivered when a peer comes in range
3. **TTL-bounded Flooding**: Each relay decrements the TTL; expired messages stop propagating
4. **Deduplication**: `seenMessageIds` prevents loops and redundant delivery
5. **Opportunistic Routing**: Uses Nearby Connections for opportunistic discovery and connection

---

## Wire Protocol

All payloads are UTF-8 encoded byte arrays. Two packet types are defined:

| Type | Format | Description |
|------|--------|-------------|
| `MSG` | `MSG\|<uuid>\|<sender>\|<ttl>\|<base64(IV+ciphertext)>` | User message, AES-GCM encrypted text |
| `ACK` | `ACK\|<originalMsgId>\|<receiverName>` | Delivery acknowledgement, plaintext |

ACKs are never stored or forwarded through the mesh.

---

## Message Status Lifecycle

```
[User sends]  →  PENDING (⏳)
                    |
              [ACK received from first peer]
                    |
               DELIVERED (✓)
```

Status is persisted in the Room database and shown in the chat UI.

---

## Architecture Components

### 1. **MainActivity** (UI Layer)
- Permission management via AndroidX Activity Result API
- Collects `TrekMeshBus.messages` (chat) and `TrekMeshBus.logs` (system log) separately
- Renders `ChatMessage` objects with label, text, and status icon

### 2. **TrekMeshService** (Networking Layer)
- Manages P2P networking lifecycle as a Foreground Service
- Handles MSG/ACK wire protocol parsing and dispatch
- Encrypts outgoing messages and decrypts incoming ones via `CryptoHelper`
- Tracks `connectedEndpoints`, `pendingEndpoints`, `endpointNames` for deduplication
- Tracks `ownMessageIds` to log delivery confirmation only for sent messages
- `listenForOutgoingMessages()` started in `onCreate()` to prevent duplicate collectors on service restart

### 3. **TrekMeshBus** (Reactive Event Bus)
- Singleton using Kotlin `StateFlow` / `SharedFlow`
- `logs`: system events (connection, errors, delivery confirmations)
- `messages`: chat messages as `ChatMessage(id, label, text, status)`
- `outgoing`: user-typed messages forwarded to the service
- `updateMessageStatus(id, status)`: updates status in the chat list reactively

### 4. **CryptoHelper** (Encryption)
- AES-256-GCM encryption/decryption
- Per-message 12-byte IV via `SecureRandom`
- Pre-shared 256-bit key hardcoded in the app

### 5. **Room Database** (Persistence)
- `MessageEntity`: id, sender, ttl, text, status, timestamp
- `MessageDao`: insert, getAll, getAllIds, updateStatus, deleteExpired, pruneOldest
- Schema version 2 (migration from v1 adds `status` column)

---

## Android Permissions

| Permission | Version | Purpose |
|-----------|---------|---------|
| `BLUETOOTH_SCAN` | Android 12+ | Scan for nearby BT devices |
| `BLUETOOTH_ADVERTISE` | Android 12+ | Advertise via Bluetooth |
| `BLUETOOTH_CONNECT` | Android 12+ | Establish BT connections |
| `NEARBY_WIFI_DEVICES` | Android 13+ | Wi-Fi Direct peers |
| `ACCESS_FINE_LOCATION` | Android <13 | Required for BLE scanning |
| `POST_NOTIFICATIONS` | Android 13+ | Foreground service notification |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Android 14+ | Service type declaration |
| `RECEIVE_BOOT_COMPLETED` | All | Auto-start after reboot |

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| `play-services-nearby` | 18.7.0 | P2P discovery, connection, payload |
| `androidx.room` | 2.6.1 | Local message persistence |
| `kotlinx-coroutines-android` | 1.8.1 | Async networking and DB access |
| `androidx.lifecycle` | 2.8.7 | Lifecycle-aware coroutine scopes |
| `androidx.core-ktx` | 1.13.1 | Android compatibility layer |

---

## Testing

### Prerequisites
- Minimum 2 Android devices (SDK 26+)
- Google Play Services updated
- Bluetooth enabled; Wi-Fi optional

### Test Steps
1. Install TrekMesh on both devices
2. Launch app on both devices; grant permissions; tap "Avvia TrekMesh"
3. Wait for automatic discovery and connection (logged in system log)
4. Send a message from Device A; observe `⏳` → `✓` transition and delivery log
5. Disconnect one device; send a message; reconnect — verify store-and-forward delivery

### Expected Log Output
```
[Hiker-A3F9C102] In ascolto di altri dispositivi...
Scansione dispositivi vicini avviata...
Dispositivo trovato: Hiker-CC74A801, richiedo connessione...
Connesso a Hiker-CC74A801
Invio 3 messaggi bufferizzati a Hiker-CC74A801...
Messaggio consegnato a Hiker-CC74A801
```

---

## Security Notes

- The pre-shared AES-256 key provides confidentiality against passive eavesdroppers who do not have the app installed
- GCM authentication tag (128-bit) detects payload tampering; tampered messages are silently dropped
- Endpoint names are ephemeral (regenerated each session via `SecureRandom`) to prevent device tracking
- A future version should replace the pre-shared key with an authenticated key exchange (e.g., ECDH)

---

## Future Enhancements

1. **Authenticated Key Exchange**: Replace pre-shared key with ECDH (Diffie-Hellman) per session
2. **Routing Metrics**: Implement PRoPHET or Epidemic routing instead of simple flooding
3. **Location Beaconing**: Attach GPS coordinates to messages for emergency scenarios
4. **Battery Indicator**: Exchange and display peer battery levels
5. **File Transfer**: Support STREAM/FILE payload types for larger data
6. **UI Polish**: Peer list with connection status, signal strength indicators

---

## License

This project is provided as-is for educational and research purposes.

---

## Author

TrekMesh - Delay Tolerant Network Stack for Android

**Target SDK**: Android 34
**Min SDK**: Android 26
**Language**: Kotlin
**Architecture**: Native Android (no external framework except Google Play Services + AndroidX)
