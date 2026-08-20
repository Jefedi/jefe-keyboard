# Jefe Keyboard

Clavier Android français QWERTY avec suggestions locales, dictée via Whisper auto-hébergé et traduction via LibreTranslate auto-hébergé.

## Prérequis

- Android 7.0 ou ultérieur (API 24+).
- JDK 17 pour compiler.
- Des URL HTTPS absolues vers vos services Whisper et LibreTranslate si vous utilisez la dictée ou la traduction.

## Construire l'APK de débogage

Sous Unix/macOS :

```bash
./gradlew assembleDebug
```

Sous Windows :

```bat
gradlew.bat assembleDebug
```

L'APK est créé à l'emplacement suivant :

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Installer et configurer

Installez directement l'APK sur un appareil connecté et autorisé :

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Ouvrez **Jefe Keyboard** depuis le lanceur.
2. Activez **Jefe Keyboard** dans les paramètres système du clavier, puis sélectionnez-le comme clavier actif.
3. Autorisez le microphone lorsque l'application le demande pour utiliser la dictée.
4. Dans l'application, saisissez les URL de base HTTPS absolues de Whisper et de LibreTranslate (par exemple `https://voice.example.net/api/` et `https://translate.example.net/`). L'application ajoute elle-même les chemins `/v1/audio/transcriptions` et `/translate`.
5. Les clés API sont facultatives ; elles sont masquées pendant la saisie et leur valeur n'apparaît jamais dans les résumés de réglages.

## Confidentialité et portée de l'artefact

La saisie et les suggestions restent sur l'appareil. La dictée et la traduction n'envoient des données qu'aux services HTTPS que vous configurez ; aucun service cloud tiers n'est imposé. L'application demande à Android de désactiver sa sauvegarde et exclut explicitement les préférences du cloud Android comme du transfert direct entre appareils, afin que les secrets enregistrés ne soient pas migrés.

Cet APK est signé avec la clé de débogage Android pour une installation et des tests directs. Ce n'est pas une version de publication ni un artefact destiné au Play Store.
