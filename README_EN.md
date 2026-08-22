# ♟️ DuLo

<p align="center">
  <a href="README.md">
    <img src="https://img.shields.io/badge/Sprache-Deutsch-red?style=for-the-badge&logo=google-translate&logoColor=white" alt="Deutsch" />
  </a>
  &nbsp;&nbsp;
  <a href="README_EN.md">
    <img src="https://img.shields.io/badge/Language-English-blue?style=for-the-badge&logo=google-translate&logoColor=white" alt="English" />
  </a>
</p>

<p align="center">
  <a href="https://github.com/styres/forkignore/actions/workflows/build-apk.yml">
    <img src="https://github.com/styres/forkignore/actions/workflows/build-apk.yml/badge.svg" alt="Build Status" />
  </a>
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84.svg?logo=android&logoColor=white" alt="Platform" />
  <img src="https://img.shields.io/badge/Language-Kotlin%20%7C%20C%2B%2B-7F52FF.svg?logo=kotlin&logoColor=white" alt="Language" />
  <img src="https://img.shields.io/badge/Engine-Stockfish%2016%20NNUE-f39c12.svg" alt="Engine" />
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License" />
  </a>
</p>

<p align="center">
  <strong>An ultra-fast, seamless real-time Android tactical assistant tailored for Duolingo Chess.</strong><br>
  Powered by precision sub-pixel board localization, an ultra-robust piece classifier, embedded Stockfish 16 + NNUE neural network engine, and a lightweight floating overlay.
</p>

---

## 🌟 Key Features

- ⚡ **Microsecond Direct Grid Scale Localization (`ChessLocator`)**
  - Gradient peak periodicity detection + residual regression algorithms eliminating reliance on fixed resolutions or specific device aspect ratios;
  - Automatically compensates for notches, gesture navigation bars, and status bar offsets with sub-pixel 8×8 grid accuracy.
- 🎯 **Ultra-Robust Dual-Anatomy Piece Classifier (`UltraRobustClassifier`)**
  - Combines head & body cosine similarity matching with edge gradient features to effortlessly distinguish tricky pieces (e.g. Pawn vs. Knight vs. Queen);
  - Adaptive 2-Means dynamic clustering ensures accurate black/white color identification regardless of tile highlights or gradient backgrounds;
  - Built-in **semantic quality gating** (dual-king validation, rank 1/8 pawn bans, duplicate piece demotion, and valid FEN verification) guaranteeing zero hallucination.
- 🧠 **Embedded Stockfish 16 + NNUE Neural Evaluation**
  - Native C++ engine builds supporting `arm64-v8a`, `armeabi-v7a`, and `x86_64` architectures;
  - Bundled with the official `nn-5af11540bbfe.nnue` neural network weights via high-speed JNI UCI protocol;
  - Millisecond-level position evaluation (Centipawns / Mate) and best-move recommendations.
- 🎨 **Native Floating Bubble & Transparent Canvas Overlay**
  - Lightweight background service (`FloatingBubbleService`) for draggable one-tap tactical analysis;
  - Transparent overlay canvas (`TransparentCanvasOverlay`) rendering live dynamic arrows directly over the Duolingo board.
- 🔒 **100% On-Device & Offline**
  - Purely offline computer vision and engine computation. No data collection, zero network requests.

---

## 📐 Architecture Pipeline

```mermaid
flowchart TD
    A[Live Screen Frame / Screenshot] --> B[ChessLocator Grid Calibration]
    B -->|Sub-pixel 8x8 Grid Slicing| C[UltraRobustClassifier Feature Extraction]
    C -->|Dual-Anatomy Cosine Matching| D[2-Means Color Clustering]
    D -->|Rule-Layer Gating & Sanitization| E[Valid FEN String Generation]
    E -->|JNI UCI Protocol Interaction| F[Stockfish 16 + NNUE Engine]
    F -->|BestMove & Eval Output| G[TransparentCanvasOverlay]
    G --> H[Render Real-time Tactical Arrows on Duolingo]
```

---

## 📂 Project Structure

```text
├── dulo/         # [Core] Android Native App Project
│   ├── app/src/main/java/   # Core source code (Locator, Classifier, FloatingService, UI)
│   ├── app/src/main/jniLibs/# Pre-compiled native Stockfish C++ libraries (.so)
│   ├── app/src/main/assets/ # Template assets and NNUE neural network weights
│   └── app/src/test/        # Unit test suite for locator, FEN builder, and UCI parser
├── test_images/             # [Dataset]
│   ├── benchmarks/          # Core positive benchmark ground truth images
│   ├── bugs/                # Real-world bug test cases & negative UI samples
│   └── calibration/         # Board scale & offset calibration images
├── tools/                   # [Utilities] Template generation, Stockfish extraction, ONNX tools
├── docs/                    # [Documentation] Architecture & design documents
└── archive/                 # [Archive] Legacy prototypes and inspection scripts
```

---

## 🚀 Getting Started

### Option 1: Download Pre-built APK

1. Go to the repository's **[Actions Page](https://github.com/styres/forkignore/actions)**;
2. Select the latest successful **`Build DuLo APK`** workflow run;
3. Scroll down to the **Artifacts** section, download and install `DuLo-APK` on your Android device (Android 8.0+).

### Option 2: Build from Source

Requirements:
- **JDK 17+**
- **Android SDK** (API Level 34, Min SDK 26)
- **Git LFS** (Required to pull the NNUE weights properly)

```bash
# 1. Clone the repository with Git LFS
git clone https://github.com/styres/forkignore.git
cd forkignore
git lfs pull

# 2. Build Debug APK with Gradle
cd dulo
./gradlew assembleDebug

# 3. Output APK location: app/build/outputs/apk/debug/app-debug.apk
```

---

## ♟️ Engine Configuration

The engine is configured during the UCI handshake for maximum strength at up to 2 seconds per move (see
`StockfishBridge`). Only options the engine advertises during the handshake are sent; anything else
is skipped and noted in the diagnostics.

| Option              | Value                                                                       |
|---------------------|-----------------------------------------------------------------------------|
| Threads             | logical cores minus 1 (e.g. 15 on 16 cores), at least 1                      |
| Hash                | 256 MB on 4-6, 512 MB on 8-12, 1024 MB from 16 logical cores                 |
| MultiPV             | 1                                                                            |
| Ponder              | false                                                                        |
| Skill Level         | 20                                                                           |
| UCI_LimitStrength   | false                                                                        |
| Move Overhead       | 10 ms (the engine runs locally on the device)                                |
| nodestime           | 0                                                                            |
| UCI_ShowWDL         | true                                                                         |
| NumaPolicy          | auto                                                                         |
| SyzygyPath          | only set when tablebases exist under `filesDir/syzygy`                       |
| SyzygyProbeDepth    | 1 (only with tablebases)                                                     |
| SyzygyProbeLimit    | 5 (only with tablebases)                                                     |
| Syzygy50MoveRule    | true (only with tablebases)                                                  |
| Search command      | `go depth 30 movetime 2000`; `ucinewgame` only at the start of a new game    |

- **Binary variant**: if variants such as `libstockfish-vnni512.so`, `-bmi2`, `-avx2`,
  `-armv8-i8mm` or `-armv8-dotprod` ship alongside the generic `libstockfish.so`, the app picks the
  strongest one supported by the CPU features found in `/proc/cpuinfo`.
- **Hash re-tuning**: when the engine reports an average `hashfull` above 30 percent, the app
  doubles the hash, up to four times the initial value.
- **Device memory cap**: the table value is capped at a quarter of physical RAM, otherwise Android
  kills the engine process on low-memory phones.
- **Large pages**: the "Lock pages in memory" privilege only exists on Windows and does not apply
  here; grant it to the user account when running the same configuration on a Windows machine.

Note on reproducibility: with multiple threads the search under `movetime` is no longer
deterministic. Results are cached per FEN, so tapping twice on an unchanged board still yields the
same recommendation.

---

## 📱 Permissions & Usage

1. **Grant Permissions**: Grant **Overlay Permission** (`SYSTEM_ALERT_WINDOW`) and **Screen Capture Permission** (`MediaProjection`) on first launch.
2. **Toggle DuLo**: The toggle button on the main screen is a photo of DuLo (the app is named after the dog). Tapping it starts the service, the picture then lights up with a green frame; tapping again stops it and the picture is dimmed. The app's user interface is German.
3. **Open Duolingo**: Launch Duolingo and enter any chess lesson or game.
4. **Open the bubble menu**: Tap the bubble to open a small menu with an **Analyse** switch (off by default) and a **Beenden** button. With the switch on, the engine is queried immediately and then again automatically whenever one of *your own* pieces has changed square; the arrow stays on screen until the next move is detected. **Beenden** closes the menu and stops the service together with the screen capture.
5. **Flip your side**: If the arrow ever suggests moves for the opponent's pieces, long-press the bubble to switch your own colour. The manual choice stays in effect until you long-press again or restart the service.

Nothing is written to disk or copied to the clipboard — screenshots stay in memory only.

---

## 🤝 Contributing

Contributions, bug reports, and feature requests are welcome! Please check out [CONTRIBUTING.md](CONTRIBUTING.md) for details.

---

## ⚖️ Disclaimer

1. This project is developed strictly for **educational and research purposes** in computer vision, on-device neural inference, and UI interaction design.
2. Please do not misuse this tool in competitive ranked games or in violation of Duolingo Terms of Service.
3. Duolingo is a registered trademark of Duolingo, Inc. The Stockfish chess engine is licensed under GPLv3.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
