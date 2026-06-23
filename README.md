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
TrekMesh acts as a **Passive Emergency Beacon** regardless of whether the mesh network is enabled:
*   **Continuous Heartbeat:** Broadcasts a BLE signal every second containing the user's ID and emergency status.
*   **GPS-Embedded Signal:** The beacon carries compressed Lat/Lon coordinates, allowing search-and-rescue (SAR) drones or helicopters to locate a hiker without a full data handshake.
*   **Passive Detection:** Rifugios and other hikers log passive detections: *"Passive SOS signal detected nearby (est. 45m)"*.
*   **Always-On:** Even with the mesh service disabled, the BLE scanner continues running in the background. A device with Bluetooth on but no active mesh connection can still detect and alert on nearby SOS beacons.

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
*   **Visual TTL Indicator:** Each received message card shows remaining hops with a color-coded badge (green ≥5, orange 2–4, red ≤1).
*   **Time-Based Expiry:** Messages are automatically purged from both DB and UI based on type and priority: BROADCAST and INFO P1–P2 expire after **6 hours**; INFO P3 and SOS expire after **24 hours**. Expiry applies to all messages including undelivered ones — a stale PENDING message would be discarded by the recipient anyway.

### 🔁 Distributed Message Lifecycle
*   **Delete & Propagate:** The sender of an INFO message can delete it from the detail screen. The deletion propagates as a `DELETE_MSG` packet through the mesh, removing the message from all nodes and stopping further forwarding.
*   **Resolve Voting:** Any user can mark a received message as resolved. When **2 distinct users** vote, a `DELETE_MSG` packet is broadcast and the message is removed from all nodes automatically.

### 🚦 Intelligent Quality of Service (QoS)
The network implements a tiered priority system in the transmission buffer:
1.  **SOS Messages:** Absolute precedence. They "jump" to the front of the queue.
2.  **Standard Info:** Direct hiker-to-hiker communication.
3.  **Area Broadcasts:** Informational alerts (Meteo, Trail status).

---

## 🏠 The Gateway Ecosystem (Rifugio Role)

Nodes can assume two distinct roles to optimize network utility:
*   **Hiker Role:** Standard profile for communication and mesh relaying.
    *   **Persistent Identity:** A random node name (e.g., `Hiker-A3F9`) is generated on first launch and reused across all sessions. It resets only if app data is cleared — read-only, not editable.
    *   **Mesh Toggle:** Hikers can disable the Nearby mesh from Settings when not in the mountains. The BLE passive SOS scanner keeps running in the background regardless, consuming negligible battery.
*   **Rifugio (Mountain Hut) Role:** Acts as a **High-Priority Safety Gateway**.
    *   **Custom Name:** Rifugio nodes can set a custom display name (e.g., *"Rifugio Stella Alpina"*) visible to all peers in the mesh. The name persists across reboots.
    *   **Cloud SOS Relay:** Automatically forwards mesh-received SOS messages to **Civil Protection** via HTTP API — exclusively available to Rifugio nodes.
    *   **Relay Confirmation:** A push notification confirms when an SOS has been successfully delivered to Civil Protection.
    *   **SOS Status Management:** Rifugio nodes can mark any SOS (received or self-sent) as **"Preso in carico"** (acknowledged) or **"Risolto"** (resolved). The status update propagates through the entire mesh — all nodes see the card update in real time: orange for acknowledged, green for resolved.
    *   **Auto-Weather Injection:** Fetches weather updates via internet (when available) and broadcasts them into the mesh every hour. The bulletin includes the fetch time (e.g., *Bollettino Meteo [14:30]: clear, Temp: 18°C*).
    *   **Trail Alerts:** Rifugios can send area-wide alerts (e.g., *"Trail 101 closed due to landslide"*) that propagate up to 15 hops.

---

## 📱 User Interface

### ✍️ Message Composer
A dedicated full-screen activity replaces the old popup dialog:
- Type selector (INFO / SOS / AVVISO), priority, text and optional description
- Photo attachment with **interactive crop** (uCrop, free-style) before sending
- Description and image are hidden in the card preview — visible only in the detail screen

### 🔔 Tap-to-Detail Notifications
Incoming message notifications open directly into a full **Message Detail Screen**, displaying:
- Type/priority badge and delivery status
- Full message text, description, and full-resolution image (no crop)
- Sender, timestamp, TTL residuo
- GPS coordinates with a one-tap **"Open in Maps"** button (launches any `geo:` compatible app; graceful fallback if none installed)
- **Delete** button for own INFO messages (propagated to all mesh nodes)
- **"Segnala come risolto"** button for received messages (deleted when 2 users vote)

### 📶 Live Mesh Status Bar
A status bar above the message tabs shows the current connection state:
- Green with peer count when nodes are reachable
- Grey "nessun nodo" when isolated but service is active
- Orange prompt to activate the mesh when the service is disabled

### 🔴 Unread Badge
The "Received" tab shows a live badge counter for unread incoming messages, reset automatically when the tab is opened.

### ⚙️ Settings
*   **Mesh Service Toggle:** Enable or disable the Nearby P2P mesh on demand. When disabled, the foreground service stays alive in **passive mode** (BLE SOS scanning only) — Nearby advertising and discovery are stopped to save battery. The passive scanner always runs regardless of this toggle.
*   **Node Name:** Rifugio nodes can edit their display name directly from Settings; hikers see their fixed device name (read-only).
*   **Notification Filter:** Choose which message types trigger notifications (All / SOS only / Info only / Disabled).
*   **Role Switch:** Change between Hiker and Rifugio roles (restarts the mesh service and refreshes the node name section).

---

## 🔒 Privacy & Security
*   **AES-256-GCM Encryption:** All message payloads, including GPS metadata, are encrypted end-to-end. Tamper protection ensures corrupted data is discarded.
*   **Bandwidth Upgrade:** The app automatically switches from Bluetooth to Wi-Fi Direct when transferring **images** for situational context.
*   **Persistent Hiker Identity:** A random name (e.g., `Hiker-A3F9`) is generated once on first launch and reused across sessions. It resets only on app data clear — never backed up or transferred between devices.
*   **No Backup of Identity Data:** Role, node name, and session preferences are excluded from Android cloud backup and device transfer — reinstalling always prompts for a fresh setup.

---

## 🛠️ Technical Stack
*   **Persistence:** Room Database with automated pruning, TTL-based expiration, and per-message lifecycle control.
*   **Location:** Fused Location Provider API with background altitude tracking.
*   **Reactive UI:** Kotlin SharedFlow/StateFlow bridge between the **Foreground Service** and the UI.
*   **Image Crop:** uCrop (free-style interactive crop before attachment).
*   **Wire Protocol:**
    | Header | UUID | Sender | TTL | Type | Priority | FileID | Encrypted GeoBlob |
    | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
    | `MSG` | `uuid` | `name` | `int` | `SOS/INFO` | `1-3` | `long` | `AES-Text+GPS` |
    | `SOS_STATUS` | `msgId` | `status` | `rifugioName` | — | — | — | — |
    | `RESOLVE_VOTE` | `msgId` | `voterName` | — | — | — | — | — |
    | `DELETE_MSG` | `msgId` | — | — | — | — | — | — |

---

## 🔑 Permissions
| Permission | Requirement |
| :--- | :--- |
| `NEARBY_WIFI_DEVICES` | Wi-Fi Direct scanning (Android 13+) |
| `BLUETOOTH_SCAN/ADVERTISE` | Peer discovery & BLE Beaconing |
| `ACCESS_FINE_LOCATION` | GPS stamping, altitude tracking & SOS coordinates |
| `POST_NOTIFICATIONS` | Incoming message and SOS relay alerts |
| `FOREGROUND_SERVICE` | Persistent safety monitoring in background |

---
**TrekMesh** - *Safety through connectivity, anywhere.*
