# Third-party software and license notices

## Project license

PhoneAI is licensed as a whole under the GNU General Public License, version 3 or (at your option) any later version (GPL-3.0-or-later). The complete GPL version 3 text is provided in the repository-root `LICENSE` file. Unless a file or directory carries a separate, compatible license notice, PhoneAI source contributions are distributed under GPL-3.0-or-later.

## nanoMuse agent tool-loop detector

- Project: [nanoMuse](https://github.com/Kingijimzy/nanoMuse)
- Upstream file: `android/src/android/app/src/main/java/com/openminis/app/agent/ToolLoopDetector.kt`
- Integrated file: `app/src/main/java/com/phoneai/app/ToolLoopDetector.kt`
- Upstream license: GNU General Public License, version 3 (GPL-3.0).
- Changes made for PhoneAI on 2026-10-31: changed the Kotlin package to `com.phoneai.app`, replaced nanoMuse's AppLogger dependency with `java.util.logging.Logger`, and added a provenance header. The detector logic and threshold behavior are derived from the upstream implementation.
- Upstream repository: https://github.com/Kingijimzy/nanoMuse

Keep this notice and the provenance header in redistributed source and binaries. When distributing an APK or other object-code build, provide recipients access to the corresponding source and comply with GPLv3 section 6.

## Existing third-party components

## Android UI Detection YOLOv8 weights

- Source: https://huggingface.co/yasirfaizahmed/android_ui_detection_yolov8
- Hugging Face model-card metadata declares Apache-2.0.
- Architecture: YOLOv8 Nano; 21 Android UI classes; 640 x 640 input as documented by the model card.
- The converted TFLite file is generated from the published `best.pt` weights by the workflow in `.github/workflows/export-ui-model.yml`.
- **License review required before public distribution:** Ultralytics' current licensing guidance separately says YOLO models are offered under AGPL-3.0 or a commercial license. That conflicts with, or at least makes the effect of, the Hugging Face model-card declaration uncertain. Do not assume that the Apache-2.0 metadata alone clears redistribution rights; confirm directly with the model author and/or Ultralytics before publishing Orion.

## Ultralytics conversion tooling

The conversion workflow uses Ultralytics YOLOv8, whose open-source distribution is licensed under AGPL-3.0. The model card's Apache-2.0 declaration does not automatically relicense the Ultralytics software or resolve every question about redistribution of converted weights. This repository uses the tooling in CI; review current upstream license terms and obtain a commercial license/legal review before distributing a commercial closed-source application. Personal use is different from publishing/distributing the app.

## Runtime

The Android application is intended to execute the bundled TFLite model on-device. No screenshot is uploaded to a model service for this detector. The detector is only invoked as a fallback when accessibility and local OCR produce a sparse target list.
