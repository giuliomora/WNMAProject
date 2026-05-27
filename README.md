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

If you want to force a specific transport during testing, you can disable the other radio on the device (for example, turn off Wi‑Fi to force BLE, or turn off Bluetooth to force Wi‑Fi Direct). The app logs and runtime behavior will reflect which transport was actually used.

---

## Key Features

### 1. **Automatic Peer Discovery & Connection**
- Devices automatically discover nearby endpoints using **Google Nearby Connections API**
- No manual pairing or user confirmation required (seamless UX)
- Simultaneous **Advertising** and **Discovery** in background
- Each device generates a unique endpoint name (e.g., `Hiker-4A2B`) for identification

### 2. **Mesh Topology (M-to-N)**
- Uses **Strategy.P2P_CLUSTER** for true mesh networking
- Unlike star topology, each device can communicate with multiple peers simultaneously
- Enables data propagation across multiple hops
- Ideal for Delay Tolerant Networks where devices may not have continuous connectivity

### 3. **Foreground Service with Doze Protection**
- Runs as a **Foreground Service** with persistent notification
- Prevents Android Doze Mode from terminating the service
- Returns `START_STICKY` to auto-recover from out-of-memory kills
- Notification provides visual indicator that P2P networking is active

### 4. **Automatic Payload Exchange**
- Upon successful connection (STATUS_OK), devices automatically exchange a **BYTES payload**
- Payload format: `"Ping ricevuto da [EndpointName]"`
- All incoming payloads are logged to **Logcat** and displayed in real-time on the UI console
- Enables real-time debugging during outdoor testing

### 5. **Real-Time UI Console**
- Live log display of all network events and payloads
- Manual start/stop button for TrekMeshService
- Displays permission status and connection diagnostics
- Useful for field testing and debugging

---

## Technical Architecture

### Connectivity Types

#### **Bluetooth Low Energy (BLE)**
- **Protocol**: BLE 4.0+ (Bluetooth 5.0 preferred)
- **Range**: ~50-100 meters in open field, ~10-30 meters indoors
- **Frequency**: 2.4 GHz ISM band
- **Power Consumption**: ~5-10 mA transmitting, <1 mA idle
- **Bandwidth**: ~125 Kbps (suitable for small payloads)
- **Use Case**: Primary transport for nearby devices; low power consumption
- **Android Requirement**: `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` permissions (Android 12+)

#### **Wi-Fi Direct (P2P)**
- **Protocol**: IEEE 802.11p/ac (5 GHz or 2.4 GHz)
- **Range**: ~100-200 meters in open field
- **Frequency**: 2.4 GHz or 5 GHz (device-dependent)
- **Power Consumption**: ~150-300 mA transmitting
- **Bandwidth**: ~11-54+ Mbps (suitable for larger payloads)
- **Use Case**: Secondary transport for longer-range or higher-bandwidth scenarios
- **Android Requirement**: `NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION` permissions (Android 13+)
- **Note**: Requires Wi-Fi direct capable hardware; automatic fallback if unavailable

#### **Selection Strategy (Google Nearby Connections)**
Google Nearby Connections API **automatically selects the best transport** based on:
1. Device capabilities (BLE vs. Wi-Fi direct support)
2. Current radio state (enabled/disabled)
3. Proximity and signal strength
4. Available bandwidth and latency requirements

The app uses **dual-mode capability**: if BLE is unavailable, Nearby switches to Wi-Fi Direct, and vice versa.

---

## Network Topology

### M-to-N Mesh Topology (P2P_CLUSTER Strategy)

```
        Device A (Hiker-1234)
           /         \
          /           \
    Device B        Device C
   (Hiker-5678)    (Hiker-9ABC)
         \            /
          \          /
           Device D (Hiker-DEF0)
```

**Characteristics:**
- **Multiple connections per device**: Each device can maintain N simultaneous connections
- **No central server/hub**: Truly decentralized
- **Self-healing**: If one device goes offline, others remain connected
- **Flood-capable**: Data can propagate through multiple hops (with proper routing logic)
- **Resilient**: Works in partial connectivity scenarios (DTN paradigm)

---

## Delay Tolerant Network (DTN) Design

TrekMesh implements DTN principles:

1. **Intermittent Connectivity**: Devices may be disconnected for extended periods and reconnect dynamically
2. **End-to-End Delay**: There is no guarantee of immediate delivery; store-and-forward semantics apply
3. **Graceful Degradation**: Network operates correctly even with poor RF conditions
4. **Asynchronous Exchange**: Messages are deposited at intermediate nodes if destination is unreachable
5. **Opportunistic Routing**: Uses Nearby Connections for opportunistic discovery and connection

---

## Android Permissions

The application requires the following runtime permissions:

### Core P2P Permissions
- **BLUETOOTH_SCAN** (Android 12+): Scan for nearby Bluetooth devices
- **BLUETOOTH_ADVERTISE** (Android 12+): Advertise this device via Bluetooth
- **BLUETOOTH_CONNECT** (Android 12+): Establish Bluetooth connections
- **NEARBY_WIFI_DEVICES** (Android 13+): Access Wi-Fi Direct peers
- **FOREGROUND_SERVICE** (all versions): Allow foreground service
- **FOREGROUND_SERVICE_CONNECTED_DEVICE** (Android 14+): Specify foreground service type

### Location Permissions (Required for BLE scanning on Android <13)
- **ACCESS_FINE_LOCATION**: High-precision location (required for BLE on Android <13)
- **ACCESS_COARSE_LOCATION**: Network-based location (fallback on Android <13)
- **Note**: On Android 13+, location is not required for BLE scanning due to privacy changes

### UI Permissions
- **POST_NOTIFICATIONS** (Android 13+): Display foreground service notification

---

## Architecture Components

### 1. **MainActivity** (UI Layer)
- **Responsibility**: User interface and permission management
- **Flow**:
  1. Request runtime permissions using AndroidX Activity Result API
  2. Display "Start TrekMesh" button
  3. Launch TrekMeshService on button click
  4. Collect and display logs from TrekMeshBus in real-time
  5. Auto-scroll console to show incoming payloads

### 2. **TrekMeshService** (Networking Layer)
- **Responsibility**: Manage P2P networking lifecycle
- **Components**:
  - **ConnectionsClient**: Google Nearby Connections API interface
  - **ConnectionLifecycleCallback**: Handle connection state changes (initiated, resolved, disconnected)
  - **EndpointDiscoveryCallback**: Discover nearby endpoints; auto-request connection
  - **PayloadCallback**: Receive payloads; emit to TrekMeshBus for UI display
- **Foreground Service**: Persistent notification with `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`
- **Lifecycle**:
  - `onCreate()`: Initialize ConnectionsClient
  - `onStartCommand()`: Start foreground notification, initiate Advertising + Discovery
  - `onDestroy()`: Cleanup Bluetooth/Wi-Fi resources

### 3. **TrekMeshBus** (Event Bus / Reactive Layer)
- **Pattern**: Singleton reactive bus using Kotlin `SharedFlow`
- **Responsibility**: Decouple networking layer from UI layer
- **Usage**:
  - TrekMeshService emits log messages via `TrekMeshBus.emitLog(message)`
  - MainActivity collects messages via `TrekMeshBus.logs.collect { ... }` in a coroutine
  - Supports multiple subscribers (extensible for future components)

---

## Runtime Behavior

### Startup Sequence
```
1. User launches MainActivity
2. MainAcitivity requests permissions (Activity Result API)
3. User grants permissions
4. User taps "Avvia TrekMesh" button
5. MainActivity starts TrekMeshService
6. TrekMeshService creates NotificationChannel
7. TrekMeshService calls startForeground() with persistent notification
8. TrekMeshService initializes ConnectionsClient
9. TrekMeshService starts Advertising (announces this device)
10. TrekMeshService starts Discovery (listens for other devices)
```

### Discovery & Connection Flow
```
Device A (Advertising + Discovery)          Device B (Advertising + Discovery)
        |                                           |
        +--- "Advertising Hiker-1234" ------->    |
        |                                           |
        |                              <------ "Advertising Hiker-5678" --+
        |                                           |
        +--- "Discovery: Found Hiker-5678" ------+
        |                                           |
        +--- requestConnection(Hiker-5678) ------->|
        |                                           |
        |                 "onConnectionInitiated"  |--- "onConnectionInitiated"
        |                           |               |          |
        |           acceptConnection(Hiker-1234) <-+---------'
        |                           |               |
        |                "onConnectionResult"       |--- "onConnectionResult"
        |                   (STATUS_OK)             |    (STATUS_OK)
        |                           |               |
        +--- sendPayload("Ping from Hiker-1234") ->|
        |                           |               |--- onPayloadReceived
        |                           |               |    -> emitLog()
        |                           |               |
        +--- onPayloadReceived <----+--- sendPayload...
        |    -> emitLog()
        |
        (Payload logged to Logcat & UI Console)
```

### Payload Format
- **Type**: BYTES
- **Encoding**: UTF-8
- **Sample**: `"Ping ricevuto da Hiker-4A2B"`
- **Flow**: Sent automatically on successful connection; not user-triggered

---

## Connectivity Options Comparison

| Feature | BLE | Wi-Fi Direct |
|---------|-----|--------------|
| **Range** | 50-100m | 100-200m |
| **Bandwidth** | ~125 Kbps | ~11-54+ Mbps |
| **Power** | ~5-10 mA | ~150-300 mA |
| **Latency** | ~10-100ms | ~1-10ms |
| **Concurrent Connections** | 4-8 | 1 (group owner) |
| **Setup Time** | ~1-2s | ~3-5s |
| **Android Support** | All devices (BLE 4.0+) | Device-dependent |

**Google Nearby Connections** automatically selects the optimal transport. In practice:
- For short-range, low-latency mesh: **BLE**
- For longer-range, higher-bandwidth: **Wi-Fi Direct** (if available)
- For best coverage: **Dual-mode** (both simultaneously)

---

## Dependencies

### Core Libraries
- **Google Nearby Connections API** (`com.google.android.gms:play-services-nearby:18.7.0`)
  - Handles discovery, connection, and payload exchange
  - Abstracts BLE/Wi-Fi Direct complexity

- **AndroidX Lifecycle & Coroutines** (`androidx.lifecycle:lifecycle-runtime-ktx:2.8.7`, `kotlinx-coroutines-android:1.8.1`)
  - Reactive UI updates with cancellation support
  - Lifecycle-aware coroutine scopes

- **AndroidX Core-KTX** (`androidx.core:core-ktx:1.13.1`)
  - Compatibility layer for newer Android features

---

## Testing

### Prerequisites
- Minimum 2 Android devices (SDK 26+)
- Google Play Services updated
- Bluetooth enabled
- (Optional) Wi-Fi enabled for Wi-Fi Direct fallback

### Test Steps
1. Install TrekMesh on both devices
2. Launch app on Device A; grant permissions; tap "Avvia TrekMesh"
3. Launch app on Device B; grant permissions; tap "Avvia TrekMesh"
4. Observe Logcat filter: `TrekMeshService`
   - Expected: "Advertising avviato", "Discovery avviato"
5. Verify console shows Endpoint discovery and Ping exchange
6. Move one device out of range and back; observe reconnection

### Logcat Output Example
```
TrekMeshService: Advertising avviato
TrekMeshService: Discovery avviato
TrekMeshService: Endpoint trovato: Hiker-5678 (endpoint-abc123)
TrekMeshService: Connessione iniziata da Hiker-5678
TrekMeshService: Connessione OK con endpoint-abc123, inviato ping
TrekMeshService: Payload da endpoint-abc123: Ping ricevuto da Hiker-1234
```

---

## Battery & Power Optimization

- **Foreground Service**: Exempts the app from Doze Mode aggressive killing
- **Adaptive Advertising**: Nearby Connections uses low-power scanning intervals
- **BLE Preference**: BLE is lower-power than Wi-Fi Direct; Nearby selects accordingly
- **Background Limitations**: On Android 11+, background radios may be throttled; foreground service minimizes impact

---

## Future Enhancements

1. **Persistent Storage**: Cache discovered peers and payload history
2. **Routing Logic**: Implement hop-based message forwarding (true DTN routing)
3. **Data Compression**: Compress payloads for bandwidth efficiency
4. **Variable Payload Types**: Support BYTES, FILE, STREAM payloads
5. **Battery Indicator**: Display remaining battery on each peer
6. **Custom Beacon Format**: Exchange device metadata (name, battery, location)
7. **Boot Receiver**: Auto-start TrekMeshService on device reboot
8. **Exemption Request**: Request Battery Optimization exemption dialog
9. **UI Enhancements**: Peer list, connection status indicators, statistics

---

## License

This project is provided as-is for educational and research purposes.

---

## Author

TrekMesh - Delay Tolerant Network Stack for Android

**Target SDK**: Android 34  
**Min SDK**: Android 26  
**Language**: Kotlin  
**Architecture**: Native Android (no external libraries except Google Play Services)

