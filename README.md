# TrekMesh - Delay Tolerant Network P2P Application

## Overview

**TrekMesh** is a native Android application designed to provide reliable, decentralized communication in disconnected environments such as mountain hiking, remote outdoor activities, or disaster recovery scenarios. Using the **Google Nearby Connections API**, it creates an autonomous **Delay Tolerant Network (DTN)** with a **dynamic mesh topology** (M-to-N). 

TrekMesh allows users to exchange text messages and images without cellular coverage or internet. It acts as a safety layer by turning every smartphone into a relay node, ensuring that critical information—especially SOS alerts—reaches its destination through a "hop-by-hop" propagation mechanism.

---

## Core Functionalities

### 1. **Adaptive Scanning Algorithm (Battery vs. Range)**
TrekMesh implements a custom duty-cycle to balance connectivity with battery life:
- **Low Power Mode (Default):** Uses Bluetooth Low Energy (BLE) and Bluetooth Classic for continuous background discovery with minimal battery impact.
- **High Power Mode (Deep Scan):** Every 3 minutes, if no peers are found, the app activates **Wi-Fi Direct** for 45 seconds to perform a "Deep Scan," extending the discovery range up to 100m+.
- **SOS Priority Boost:** Sending an SOS message immediately forces the app into High Power Mode to maximize the probability of reaching a nearby rescuer or gateway.

### 2. **Mesh Networking & Flood Routing**
- **Topology:** Uses `Strategy.P2P_CLUSTER` to allow any device to connect to any number of peers.
- **Multi-hop Routing:** Implements a controlled flooding mechanism. When a message is received, the node decrements its **TTL (Time To Live)** and re-broadcasts it to all other connected peers.
- **Deduplication:** A persistent `seenMessageIds` cache ensures that redundant messages are ignored, preventing network loops.

### 3. **Store-and-Forward Intelligence**
- **Persistence:** All messages (Sent, Received, Pending) are stored in a local **Room Database**.
- **Opportunistic Delivery:** If no peers are in range, messages are buffered. As soon as a connection is established, the app "flushes" the pending buffer to the new peer.
- **Auto-Cleanup:** Expired messages (TTL=0) and old history are automatically pruned to keep the database lean.

### 4. **User Roles & Emergency Gateway (SOS Relay)**
- **Hiker Role:** The standard profile for peer-to-peer communication and mesh relaying.
- **Rifugio (Mountain Hut) Role:** Acts as a **High-Priority Gateway**. 
    - When a Rifugio node receives an **SOS** message, it attempts to forward it to the **Protezione Civile** (Civil Protection) via an external HTTP/JSON API.
    - **Offline Queueing:** If the Rifugio is offline, the SOS is queued. The app uses a `NetworkCallback` to automatically retry the transmission the moment internet connectivity is restored.

### 5. **Rich Media & Bandwidth Management**
- **Image Support:** Users can attach photos to messages (e.g., photo of an injury, map detail, or landmark).
- **Auto-Upgrade:** The app leverages Nearby Connections to automatically perform a **Bandwidth Upgrade** (switching from Bluetooth to Wi-Fi Direct) for large file payloads, ensuring fast and reliable image transfers.

### 6. **Privacy & End-to-End Security**
- **AES-256-GCM Encryption:** All message payloads are encrypted end-to-end. 
- **Tamper Protection:** The GCM authentication tag ensures that any modified or corrupted message is silently discarded.
- **Ephemeral Identity:** To prevent device tracking, each session generates a random name (e.g., `Hiker-4B2A`) using `SecureRandom`.

---

## User Interface & Experience

- **Split-View Dashboard:** 
    - **Chat Area:** Displays received and sent messages with user-friendly labels.
    - **System Log:** A real-time log of network events (Discovery, Connection, Handshakes, ACKs).
- **Delivery Status Tracking:**
    - `⏳ PENDING`: Message is in the local buffer or mesh.
    - `✓ DELIVERED`: A point-to-point **ACK** was received from at least one peer.
- **Smart Notifications:**
    - Standard messages trigger a standard notification.
    - **SOS Alerts** use a high-importance channel with `PRIORITY_MAX`, `CATEGORY_ALARM`, and a distinct vibration pattern to ensure they are noticed even in noisy environments.

---

## Technical Stack & Architecture

- **Foreground Service:** The networking engine runs as a persistent service with `START_STICKY` to survive system memory pressure.
- **Boot Auto-Start:** A `BroadcastReceiver` listens for `BOOT_COMPLETED` to restart the mesh service automatically when the phone is turned on.
- **Reactive Event Bus:** Uses Kotlin `StateFlow` and `SharedFlow` (`TrekMeshBus`) to bridge the background service and the UI fragments.
- **Wire Protocol:**
    - `MSG|<uuid>|<sender>|<ttl>|<type>|<priority>|<fileId>|<encryptedBlob>`
    - `ACK|<msgId>|<ackerName>`

---

## Permissions
TrekMesh requires the following permissions to operate on modern Android versions (12, 13, 14+):
- **Nearby Devices:** `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES`.
- **Location:** `ACCESS_FINE_LOCATION` (Required for radio hardware access on older versions).
- **Service:** `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`.

---

## Testing Procedure
1. Install the app on two or more devices.
2. Grant all permissions and select roles.
3. Observe the Log area for `[Hiker-XXXX] Listening...`.
4. Send a message and watch it propagate. 
5. Simulate "Hiker" and "Rifugio" interactions by toggling Wi-Fi/Airplane mode to test the Store-and-Forward and SOS Relay features.

---
**TrekMesh** - *Connecting the disconnected, saving lives through Mesh.*
