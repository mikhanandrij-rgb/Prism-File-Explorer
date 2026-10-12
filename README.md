<div align="center">

<img src="assets/app_icon.png" width="120" alt="Facet Files logo"/>

# Facet Files

[![Platform](https://img.shields.io/badge/Platform-Android-brightgreen.svg?logo=android)](https://www.android.com/)
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![CI](https://github.com/mikhanandrij-rgb/Prism-File-Explorer/actions/workflows/ci.yml/badge.svg)](https://github.com/mikhanandrij-rgb/Prism-File-Explorer/actions/workflows/ci.yml)

**A modern, ad-free, tracker-free file manager for Android, built with Kotlin and Jetpack Compose.**

</div>

> [!NOTE]
> **Facet Files is a maintained fork of [Prism File Explorer](https://github.com/Raival-e/Prism-File-Explorer) by [Raival-e](https://github.com/Raival-e).**
> The original project is no longer maintained (last release v1.3.2, September 2025). All credit for the original
> design and codebase goes to Raival-e. This fork is distributed under the same GNU GPL v3.0 license.
>
> Facet Files uses its own application ID (`io.github.mikhanandrij_rgb.facet`), so it installs side by side with the original.

## 🔒 Privacy

- No ads, no analytics, no trackers.
- Network access happens only on an explicit user action.

---

## 📱 Screenshots

<div align="center">
  <img src="assets/image1.png" width="22%" alt="Main Interface"/>
  <img src="assets/image2.png" width="22%" alt="File Operations"/>
  <img src="assets/image3.png" width="22%" alt="Apps Extractor"/>
  <img src="assets/image4.png" width="22%" alt="Text Editor"/>
</div>

<div align="center">
  <img src="assets/image6.png" width="21%" alt="Audio Player"/>
  <img src="assets/image7.png" width="21%" alt="Image Viewer"/>
  <img src="assets/image5.png" width="21%" alt="PDF Viewer"/>
</div>

*Screenshots are from the original Prism File Explorer and will be updated.*

---

## ✨ Key Features

### 🎨 **Modern User Interface**

- **Jetpack Compose UI**: Fully declarative and responsive interface
- **Material Design 3**: Beautiful, consistent design language
- **Dark/Light Theme**: Automatic theme switching support
- **Smooth Animations**: Fluid transitions and interactions

### 📁 **Comprehensive File Management**

- **Complete File Operations**: Create, copy, move, rename, delete files and folders
- **Advanced Selection**: Multi-select with intuitive gestures
- **Smart Cut/Copy/Paste**: Clipboard operations with visual feedback
- **Batch Operations**: Perform actions on multiple files simultaneously
- **File Properties**: Detailed information about files and directories

### 🗂️ **Advanced Features**

- **Multi-Tab Interface**: Manage multiple directories simultaneously
- **Quick Navigation**: Breadcrumb navigation and quick access shortcuts
- **Search Functionality**: Find files and folders quickly
- **Sorting Options**: Sort by name, size, date, type with ascending/descending order
- **View Modes**: Grid and list view options

### 📺 **Built-in Media Viewers**

- **Image Viewer**: Support for JPEG, PNG, GIF, WebP, and more
- **Video Player**: Play MP4, AVI, MKV, MOV, and other formats
- **Audio Player**: MP3, WAV, FLAC, OGG playback support
- **PDF Viewer**: View PDF documents natively
- **Text Editor**: Syntax highlighting for code files

### 🗜️ **Archive Management**

- **Extract Archives**: Support for ZIP format
- **Create Archives**: Compress files and folders into ZIP format
- **Archive Preview**: Browse archive contents without extraction

### ⚡ **Performance & Efficiency**

- **Lightweight**: Minimal resource usage and fast startup
- **Optimized**: Smooth performance on low-end devices
- **Background Operations**: Non-blocking file operations
- **Memory Efficient**: Smart memory management for large directories

---

## 🎬 Video player

The video player is built on **libmpv** (mpv 0.41, FFmpeg, libplacebo, libass):

- Hardware decoding (MediaCodec) rendered through `gpu-next`, all common codecs and containers
  (H.264, HEVC incl. 10-bit, AV1, VP9, MKV, MP4, AVI, MOV, FLAC, Opus, DTS, …)
- **Real-time neural upscaling for anime**, ranked by measured quality: ArtCNN (C4F16/C4F32,
  plain and DS), Anime4K (modes A, B, C, A+A, B+B, C+A; levels Fast to
  Ultra, official mpv chains) and experimental Anime4K GAN (x3 VL, x4 UUL). Shaders are rewritten
  for mobile GLSL ES at install time. Upscaling only runs when the screen is larger than the
  video, and the choice never changes by itself; the stats overlay shows the render time per
  frame against the frame budget.
- Your own mpv `.glsl` shaders: import them and combine them into presets
- ASS/SSA subtitles with styles and embedded fonts, external subtitles next to the file,
  audio/subtitle track selection
- Optional stats overlay (resolution, decoder, FPS, dropped frames, render passes and GPU time)
- Keeps playing through screen rotation; pauses for calls and when headphones are unplugged

APKs are split per CPU architecture (`arm64-v8a` for almost all phones).

---

## 📥 Download

Builds from every commit are available as `apk-debug` artifacts in
[GitHub Actions](https://github.com/mikhanandrij-rgb/Prism-File-Explorer/actions/workflows/ci.yml).
Signed releases, IzzyOnDroid and F-Droid listings are planned.

### 📋 System Requirements

- **Android Version**: 6.0 (API 23) or higher
- **Permissions**: Storage access for file management

---

## 🛠️ Built With

| Technology                                                                                                               | Purpose                  | Version       |
|--------------------------------------------------------------------------------------------------------------------------|--------------------------|---------------|
| ![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white)                             | **Programming Language** | Latest        |
| ![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=flat&logo=jetpackcompose&logoColor=white) | **UI Framework**         | Latest Stable |
| ![Android](https://img.shields.io/badge/Android-3DDC84?style=flat&logo=android&logoColor=white)                          | **Platform**             | SDK 34+       |
| ![Material Design](https://img.shields.io/badge/Material%20Design-757575?style=flat&logo=materialdesign&logoColor=white) | **Design System**        | Material 3    |

---

## 🔨 Building from Source

Prerequisites: JDK 17 or newer, Android SDK (compileSdk 36).

```bash
git clone https://github.com/mikhanandrij-rgb/Prism-File-Explorer.git
cd Prism-File-Explorer
./gradlew assembleDebug
```

The APK will be in `app/build/outputs/apk/debug/`.

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE).

```
Facet Files
Copyright (C) 2024-2025 Raival-e (original Prism File Explorer)
Copyright (C) 2026 Facet Files contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.
```

## 📦 Third-party components

| Component | License |
|---|---|
| [mpv](https://mpv.io) | LGPL-2.1+ / GPL-2.0+ |
| [FFmpeg](https://ffmpeg.org) | LGPL-2.1+ / GPL-2.0+ |
| [libplacebo](https://code.videolan.org/videolan/libplacebo) | LGPL-2.1+ |
| [libass](https://github.com/libass/libass) | ISC |
| [dav1d](https://code.videolan.org/videolan/dav1d) | BSD-2-Clause |
| [libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android) (packaging) | MIT |
| [Anime4K](https://github.com/bloc97/Anime4K) shaders | MIT (`app/src/main/assets/anime4k/LICENSE.txt`) |
| [ArtCNN](https://github.com/Artoriuz/ArtCNN) shaders | MIT (`app/src/main/assets/artcnn/LICENSE.txt`) |

All are compatible with this project's GPL-3.0 license.

## 🙏 Credits

- [Raival-e](https://github.com/Raival-e) — author of the original
  [Prism File Explorer](https://github.com/Raival-e/Prism-File-Explorer).
  If you want to support the original author: [Buy Me A Coffee](https://www.buymeacoffee.com/RaivalR).
- Everyone who contributed translations and patches to the original project.
