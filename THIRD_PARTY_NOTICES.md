# Third-party model and tooling notices

## Android UI Detection YOLOv8 weights

- Source: https://huggingface.co/yasirfaizahmed/android_ui_detection_yolov8
- Model card license: Apache-2.0 (verify the current repository license and notices before redistributing).
- Architecture: YOLOv8 Nano; 21 Android UI classes; 640 x 640 input as documented by the model card.
- The converted TFLite file is generated from the published `best.pt` weights by the workflow in `.github/workflows/export-ui-model.yml`.

## Ultralytics conversion tooling

The conversion workflow uses Ultralytics YOLOv8, whose open-source distribution is licensed under AGPL-3.0. The model card's Apache-2.0 declaration does not automatically relicense the Ultralytics software or resolve every question about redistribution of converted weights. This repository uses the tooling in CI; review current upstream license terms and obtain a commercial license/legal review before distributing a commercial closed-source application. Personal use is different from publishing/distributing the app.

## Runtime

The Android application is intended to execute the bundled TFLite model on-device. No screenshot is uploaded to a model service for this detector. The detector is only invoked as a fallback when accessibility and local OCR produce a sparse target list.
