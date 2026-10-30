# FlowScribe

Dictée vocale universelle pour Android 15+. Une bulle flottante, un tap pour parler,
un tap pour arrêter : le texte est transcrit par IA puis inséré dans le champ actif
de n'importe quelle application, sans changer de clavier.

Application personnelle, non open source.

## Fonctionnement

1. La bulle flottante est lancée depuis l'application (jamais au démarrage, à cause
   des restrictions Android 15 sur le micro en arrière-plan).
2. Un tap démarre l'enregistrement (16 kHz mono), un second l'arrête. Le silence en
   début et en fin est coupé avant l'envoi.
3. Le WAV est envoyé à **Gemini** (`gemini-3.5-flash-lite` par défaut) ou, en secours,
   à **Groq Whisper** (`whisper-large-v3-turbo`).
4. Le texte est ponctué, corrigé avec le dictionnaire personnel, puis inséré via le
   service d'accessibilité (`ACTION_SET_TEXT` au curseur, sinon presse-papiers + collage).
5. En cas de champ sécurisé ou d'app récalcitrante, le texte est copié et une
   notification l'indique.

## Consommation maîtrisée

- Un seul appel IA par dictée par défaut (pas de requête « hedged », pas d'aperçu live).
- Silence coupé et plafond de sortie calculé selon la durée réelle de l'audio.
- Modèles économiques par défaut : `gemini-3.5-flash-lite` et `whisper-large-v3-turbo`.
- Rotation de 2 clés maximum par tentative, refroidissement sur 401/403/429/5xx.
- Jusqu'à 9 clés par fournisseur, chiffrées par le Keystore Android.
- Réglage « Optimiser » dans Réglages pour revenir au preset économie en un tap.

## Stack

- Kotlin 2.1.10, Jetpack Compose (BOM 2025.02.00), Material 3 / Material You.
- minSdk / targetSdk / compileSdk 35 (Android 15).
- DataStore Preferences + kotlinx.serialization (pas de Room).
- OkHttp, coroutines, EncryptedSharedPreferences pour les clés.
- Aucun serveur intermédiaire, aucune analytics.

## Build

Prérequis : JDK 17, Android SDK 35.

```bash
export JAVA_HOME=/chemin/vers/jdk17
export ANDROID_HOME=/chemin/vers/android-sdk
./gradlew :app:assembleRelease --no-daemon --max-workers=2
```

Le build est volontairement limité en mémoire dans `gradle.properties`
(`-Xmx1536m`, workers 2, pas de daemon persistant).

La signature release utilise `signing/flowscribe-release.jks` et
`signing/keystore.properties`. Ce dépôt étant privé, ils sont conservés pour que les
mises à jour gardent la même signature. Ne jamais publier ces fichiers.

APK généré : `app/build/outputs/apk/release/app-release.apk`.

## Installation sur HyperOS

1. Installer l'APK (autoriser « Installer des applications inconnues » pour le
   gestionnaire de fichiers utilisé).
2. Ouvrir FlowScribe et suivre l'onboarding : micro, fenêtres flottantes,
   accessibilité, notifications, batterie sans restriction.
3. Sur HyperOS, ouvrir en plus les réglages de l'app et autoriser :
   - « Afficher en fenêtre surgissante » ;
   - « Démarrage automatique » ;
   - « Économiseur de batterie : aucune restriction » ;
   - verrouiller l'app dans les tâches récentes (cadenas).
4. Lancer la bulle depuis l'accueil, puis ouvrir n'importe quel champ de texte.

## Limites connues

- Certaines apps (banques, champs `password`) refusent l'insertion : le texte est copié.
- Le délai final dépend du réseau et du fournisseur ; viser < 0,9 s est réaliste avec
  Groq turbo sur une dictée courte, mais pas garanti sur réseau mobile.
- Les quotas Groq sont partagés au niveau du compte : plusieurs clés du même compte
  ne contournent pas les 20 req/min ni les 7 200 s d'audio/heure.
