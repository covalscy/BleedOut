# BleedOut — Down-But-Not-Out & Revive Engine (Forge 1.20.1)

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-brightgreen?style=flat-square&logo=minecraft)
![Forge](https://img.shields.io/badge/Forge-47.2.0+-orange?style=flat-square)
![Java](https://img.shields.io/badge/Java-17-blue?style=flat-square&logo=openjdk)
![License](https://img.shields.io/badge/License-MIT-lightgrey?style=flat-square)

**BleedOut** is a high-concurrency Down-But-Not-Out (DBNO) and player revive system for Minecraft 1.20.1. It features real-time packet-synchronized bleedout state transitions, custom crawling physics, teammate revive progress mechanics, and visual HUD indicators.

---

## 🌟 Key Features

### 🩸 Down-But-Not-Out (DBNO) State Machine
- **Bleedout Timer & Lifecycle:** When lethal damage is taken, players enter a downed state with a configurable countdown timer instead of instantly dying.
- **Crawling & Movement Overrides:** Restricts movement speed and allows downed players to crawl to cover while awaiting rescue.
- **Camera & Post-Processing Effects:** Applied visual vignette, grayscale/darkening camera filters, and heartbeat audio feedback during bleedout.

### 🤝 Squad Teammate Revive Mechanics
- **Interactive Hold-to-Revive:** Teammates can interact with downed players to initiate a progress-bar revive cycle.
- **Tactical HUD Markers:** `KnockedTeammateRenderer` renders 3D world-space beacons and status indicators over knocked squadmates.
- **Give-Up / Suicide Request:** Downed players can hold a keybind to trigger an instant respawn (`SuicideRequestPacket`).

### ⚡ Low-Latency Networking
- **Bi-directional Packet Sync:** Custom networking layer (`BleedOutNetwork`) syncing state updates (`BleedOutUpdatePacket`, `ReviveUpdatePacket`).

---

## 🏗️ Build & Installation

### Requirements
- **Java:** JDK 17 or higher
- **Build Tool:** Gradle (wrapper included)
- **Mod Loader:** Minecraft Forge 1.20.1 (MDK 47.2.0+)

### Building from Source
```bash
git clone https://github.com/covalscy/BleedOut.git
cd BleedOut
./gradlew build
```
The compiled output JAR will be generated in `build/libs/`.

---

## 📄 License
This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.
