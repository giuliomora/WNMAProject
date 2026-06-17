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

### 2. **BLE Beaconing (Passive SOS "Digital Flare")**
In addition to active connections, TrekMesh acts as a **digital emergency beacon**:
- **Continuous Advertising:** The app broadcasts a low-energy Bluetooth signal (Beacon) every second.
- **Search & Rescue Friendly:** The signal can be picked up by rescue drones, helicopters, or other users even without establishing a formal data connection.
- **Passive Detection:** If a "Rifugio" or another user captures an SOS beacon nearby, the app immediately logs an alert: *"Passive SOS signal detected nearby!"*.
- **Zero-Config:** Works in the background even when the main mesh scanning is in "sleep" mode, providing a constant "heartbeat" safety signal.

### 3. **Mesh Networking & Flood Routing**
- **Topology:** Uses `Strategy.P2P_CLUSTER` to allow any device to connect to any number of peers.
- **Multi-hop Routing:** Implements a controlled flooding mechanism. When a message is received, the node decrements its **TTL (Time To Live)** and re-broadcasts it to all other connected peers.
- **Deduplication:** A persistent `seenMessageIds` cache ensures that redundant messages are ignored, preventing network loops.

### 4. **Store-and-Forward Intelligence**
- **Persistence:** All messages (Sent, Received, Pending) are stored in a local **Room Database**.
- **Opportunistic Delivery:** If no peers are in range, messages are buffered. As soon as a connection is established, the app "flushes" the pending buffer to the new peer.
- **Auto-Cleanup:** Expired messages (TTL=0) and old history are automatically pruned to keep the database lean.

### 5. **User Roles & Emergency Gateway (SOS Relay)**
- **Hiker Role:** The standard profile for peer-to-peer communication and mesh relaying.
- **Rifugio (Mountain Hut) Role:** Acts as a **High-Priority Gateway**. 
    - When a Rifugio node receives an **SOS** (via Mesh message or Beacon), it alerts the keeper.
    - **Cloud Integration:** SOS messages received via mesh are forwarded to the **Protezione Civile** (Civil Protection) via an external HTTP API.
    - **Offline Queueing:** If the Rifugio is offline, the SOS is queued for automatic retry the moment internet connectivity is restored.

### 6. **Rich Media & Bandwidth Management**
- **Image Support:** Users can attach photos to messages (e.g., photo of an injury or landmark).
- **Auto-Upgrade:** The app automatically performs a **Bandwidth Upgrade** (switching from Bluetooth to Wi-Fi Direct) for image transfers.

### 7. **Privacy & End-to-End Security**
- **AES-256-GCM Encryption:** All mesh message payloads are encrypted end-to-end. 
- **Ephemeral Identity:** Each session generates a random name (e.g., `Hiker-4B2A`) to prevent device tracking.

---

## User Interface & Experience

- **Split-View Dashboard:** 
    - **Chat Area:** Displays received and sent messages with status icons (`⏳ PENDING`, `✓ DELIVERED`).
    - **System Log:** Real-time log of network events, handshakes, and **Passive SOS detections**.
- **Smart Notifications:**
    - **SOS Alerts** use a dedicated high-importance channel with `PRIORITY_MAX` and a custom vibration pattern.

---

## Technical Stack

- **Foreground Service:** Persistent networking engine with `START_STICKY`.
- **Boot Auto-Start:** Automatically restarts the safety service upon device reboot.
- **BLE Beacon Tech:** Uses `BluetoothLeAdvertiser` and `BluetoothLeScanner` with a custom UUID.
- **Wire Protocol:**
    - `MSG|<uuid>|<sender>|<ttl>|<type>|<priority>|<fileId>|<encryptedBlob>`
    - `ACK|<msgId>|<ackerName>`

---

## Permissions
TrekMesh requires the following for Android 12-14+:
- `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`.
- `NEARBY_WIFI_DEVICES` & `ACCESS_FINE_LOCATION`.
- `POST_NOTIFICATIONS` & `FOREGROUND_SERVICE_CONNECTED_DEVICE`.

---
**TrekMesh** - *Safety through connectivity, anywhere.*
