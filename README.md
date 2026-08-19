# Jefe Keyboard

Clavier Android avec dictée vocale (Whisper) et traduction (LibreTranslate) self-hosted.

## Features
- Layout QWERTY + chiffres visibles
- Prédiction de mots en français
- Dictée vocale → votre serveur Whisper
- Traduction du texte sélectionné → votre serveur LibreTranslate
- URLs et clés API configurables dans l'app
- Zéro Google, zéro cloud tiers

## Setup
1. Installer l'APK
2. Activer le clavier: Paramètres → Système → Clavier → Jefe Keyboard
3. Ouvrir l'app → configurer les URLs Whisper et LibreTranslate

## Build
```bash
./gradlew assembleDebug
```

Windows: `gradlew.bat assembleDebug`

APK: `app/build/outputs/apk/debug/app-debug.apk`
