# 🏔️ TrekMesh: Delay Tolerant P2P Safety Network

**TrekMesh** is a professional-grade Android application designed for decentralized communication in extreme environments. Built on the **Google Nearby Connections API**, it transforms standard smartphones into resilient nodes of a **Delay Tolerant Mesh Network (DTN)**, enabling text, image, and emergency data exchange where cellular and internet infrastructures fail.

---

## 🚀 Core Innovation: The Adaptive Safety Stack

TrekMesh goes beyond simple P2P chat by implementing a multi-layered safety protocol designed for the unique challenges of mountain hiking and wilderness exploration.

### 🔋 1. Adaptive Scanning Algorithm
To survive multi-day treks, TrekMesh balances connectivity with battery longevity through an intelligent duty-cycle:
*   **Low Power Mode (Default):** Uses **Bluetooth Low Energy (BLE)** and Bluetooth Classic for continuous background discovery with negligible battery impact.
*   **High Power Mode (Deep Scan):** Every **3 minutes**, the app activates **Wi-Fi Direct** for 45 seconds. This "Deep Scan" extends discovery range up to **100m+**, finding peers that Bluetooth might miss.
*   **SOS Priority Boost:** Any outgoing SOS message instantly overrides the duty-cycle, forcing High Power Mode to maximize the probability of reaching a rescuer or gateway.

### 📡 2. BLE Beaconing ("Digital SOS Flare")
Even when the mesh engine is in its sleep cycle, TrekMesh acts as a **Passive Emergency Beacon**:
*   **Continuous Heartbeat:** Broadcasts a BLE signal every second containing the user's ID and emergency status.
*   **GPS-Embedded Signal:** The beacon carries compressed Lat/Lon coordinates, allowing search-and-rescue (SAR) drones or helicopters to locate a hiker without a full data handshake.
*   **Passive Detection:** Rifugios and other hikers log passive detections: *"Passive SOS signal detected nearby (est. 45m)"*.

### 🛡️ 3. Proactive Survival Tools
*   **Virtual Breadcrumbs:** Automatically records GPS coordinates every 15 minutes. Upon an **SOS** trigger, the last 5 positions are attached to the alert, showing rescuers the hiker's path and direction of travel.
*   **Safety Timer (Dead Man's Switch):** Users can set a countdown (30m, 1h, 2h) via long-press on the SOS button. If the user doesn't press **"I'M OK"** before expiry, the app automatically broadcasts a high-priority SOS with current and historical location data.
*   **Automatic GPS Stamping:** Every message is cryptographically "stamped" with the sender's Lat/Lon/Alt, enabling real-time proximity awareness.

## 🕸️ Mesh Architecture & Intelligence

TrekMesh creates a self-healing, multi-hop network using the `Strategy.P2P_CLUSTER` topology.

### 🔄 Flood Routing with TTL
Messages propagate through the network based on a **Time To Live (TTL)** system:
*   **Standard Messages:** 7 hops (can cover several kilometers in a populated trail).
*   **Area Broadcasts:** 15 hops (designed to cover wide valleys from a high-altitude gateway).
*   **Deduplication:** A persistent cache ensures nodes never process or forward the same message twice, preventing "broadcast storms."

### 🚦 Intelligent Quality of Service (QoS)
The network implements a tiered priority system in the transmission buffer:
1.  **SOS Messages:** Absolute precedence. They "jump" to the front of the queue.
2.  **Standard Info:** Direct hiker-to-hiker communication.
3.  **Area Broadcasts:** Informational alerts (Meteo, Trail status).

---

## 🏠 The Gateway Ecosystem (Rifugio Role)

Nodes can assume two distinct roles to optimize network utility:
*   **Hiker Role:** Standard profile for communication and mesh relaying.
*   **Rifugio (Mountain Hut) Role:** Acts as a **High-Priority Safety Gateway**.
    *   **Cloud SOS Relay:** Automatically forwards mesh-received SOS messages to **Civil Protection** via HTTP API.
    *   **Auto-Weather Injection:** Fetches weather updates via internet (when available) and broadcasts them into the mesh every hour.
    *   **Trail Alerts:** Rifugios can send area-wide alerts (e.g., *"Trail 101 closed due to landslide"*) that propagate up to 15 hops.

---

## 🔒 Privacy & Rich Media
*   **AES-256-GCM Encryption:** All message payloads, including GPS metadata, are encrypted end-to-end. Tamper protection ensures corrupted data is discarded.
*   **Bandwidth Upgrade:** The app automatically switches from Bluetooth to Wi-Fi Direct when transferring **images** for situational context.
*   **Ephemeral Identity:** Random session names (e.g., `Hiker-A3F9`) prevent long-term tracking of hikers.

---

## 🛠️ Technical Stack
*   **Persistence:** Room Database (v6) with automated pruning and TTL-based expiration.
*   **Location:** Fused Location Provider API with background altitude tracking.
*   **Reactive UI:** Kotlin SharedFlow/StateFlow bridge between the **Foreground Service** and the UI.
*   **Wire Protocol:**
    | Header | UUID | Sender | TTL | Type | Priority | FileID | Encrypted GeoBlob |
    | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
    | `MSG` | `uuid` | `name` | `int` | `SOS/INFO` | `1-3` | `long` | `AES-Text+GPS` |

---

## 🔑 Permissions
| Permission | Requirement |
| :--- | :--- |
| `NEARBY_WIFI_DEVICES` | Wi-Fi Direct scanning (Android 13+) |
| `BLUETOOTH_SCAN/ADVERTISE` | Peer discovery & BLE Beaconing |
| `ACCESS_FINE_LOCATION` | Accurate GPS stamping & Altitude |
| `FOREGROUND_SERVICE` | Persistent safety monitoring in background |

---
**TrekMesh** - *Safety through connectivity, anywhere.*
