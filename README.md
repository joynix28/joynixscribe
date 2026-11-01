# JoynixScribe

**Dictée vocale universelle pour Android et Windows** — une bulle flottante sur
mobile, une barre flottante sur desktop, un raccourci pour parler : le texte est
transcrit par IA puis inséré dans le champ actif de n'importe quelle application.

JoynixScribe est open source (Kotlin + Jetpack Compose pour Android, Tauri v2 +
Rust pour Windows) et transforme la voix en texte là où tu tapes : SMS, e-mail,
Discord, navigateur, notes, formulaires, n'importe quel logiciel Windows.

## Téléchargement

| Plateforme | Fichier |
|---|---|
| Android (APK signé) | [JoynixScribe-2.4.0.apk](./JoynixScribe-2.4.0.apk) |
| Windows (installeur, recommandé) | [JoynixScribe-Setup-2.4.0.exe](./desktop/JoynixScribe-Setup-2.4.0.exe) |
| Windows (portable, sans installation) | [JoynixScribe-2.4.0-portable.zip](./JoynixScribe-2.4.0-portable.zip) |

La version portable est une archive : décompressez-la et gardez
`WebView2Loader.dll` à côté de `JoynixScribe.exe`. Windows 10/11 doit disposer
du runtime [Microsoft Edge WebView2](https://developer.microsoft.com/microsoft-edge/webview2/)
(préinstallé sur Windows 11 et sur la plupart des Windows 10 à jour).

## Présentation

- **Bulle flottante** toujours accessible, personnalisable (couleur, icône, taille,
  opacité, geste tap ou maintenir-pour-parler).
- **3 moteurs IA à clé** : Google Gemini, Groq Whisper, Cloudflare Workers AI, avec **Aperture Sciences en secours sans clé**.
- **Mode streaming (live)** : le texte apparaît pendant que tu parles, segmenté sur
  les silences, sans attendre la fin.
- **Styles adaptatifs** : le style de sortie se choisit automatiquement selon
  l'application active (détection du nom et du package).
- **Dictionnaire personnel**, ponctuation automatique, typographie française.
- **Confidentialité** : l'écran n'est jamais lu ni capturé ; l'audio ne quitte
  l'appareil que pour le fournisseur choisi.
- **Compteurs d'usage** par clé (requêtes, tokens, secondes audio, neurons) et
  plafond quotidien optionnel.
- **Open source**, sans analytics, sans serveur intermédiaire.

## Fonctionnalités

- Bulle flottante personnalisable : couleur, icône, taille, opacité, geste (tap ou
  maintenir pour parler puis relâcher pour envoyer).
- Trois fournisseurs à clé : Google Gemini, Groq Whisper, Cloudflare Workers AI. Secours sans clé : Aperture Sciences.
- Styles de sortie adaptables à l'application active (détection du nom et du package).
- Dictionnaire personnel, ponctuation automatique, typographie française.
- Historique local, export de configuration (sans les clés).
- Compteurs d'usage par clé : requêtes, tokens, secondes audio, neurons.
- Plafond quotidien optionnel par clé.
- Insertion via service d'accessibilité, avec repli presse-papiers/collage.

## Confidentialité

- L'écran n'est jamais lu ni capturé. Seuls le nom et le package de l'application
  active peuvent être transmis au modèle, et uniquement si l'option est activée.
- L'audio est envoyé uniquement au fournisseur choisi, puis oublié.
- Les clés API sont chiffrées par le Keystore Android. Elles ne sont jamais
  incluses dans l'export ni dans un dépôt Git.
- Aucune analytics, aucun serveur intermédiaire.

## Fournisseurs et limites gratuites

| Fournisseur | Limite gratuite | Remarque |
|---|---|---|
| Google Gemini | Selon le projet Google AI Studio | Quota partagé par le projet, pas par clé |
| Groq Whisper | 20 req/min · 2 000 req/jour · 7 200 s audio/heure · 28 800 s/jour | Quota partagé par le compte |
| Cloudflare Workers AI | 10 000 neurons/jour/compte | ~1,5 neuron pour 2 s de whisper-turbo |

Plusieurs clés du même compte ne contournent pas les quotas : ils sont liés au
compte/projet. JoynixScribe fait un seul appel par dictée par défaut ; le mode
« repli anticipé » (deux appels en parallèle) est désactivable.

## Configurer Cloudflare Workers AI

1. Crée un compte sur <https://dash.cloudflare.com>.
2. Dans le menu AI → Workers AI, clique sur « Get Account ID » (32 caractères,
   non secret).
3. Crée un token API avec la permission « Workers AI : Read/Edit »
   (Account → Workers AI → Edit).
4. Dans JoynixScribe → Clés → Cloudflare, colle `accountId|token`
   (une barre verticale entre les deux).
5. Touche la fiole pour tester. Le test lit le catalogue de modèles : aucun
   crédit audio consommé.

Le token reste chiffré sur le téléphone. Ne le colle jamais dans un dépôt Git,
une capture ou un message public.

## Secours audio : Aperture

L'endpoint `https://aperture-sciences.com/api/audio/transcribe` accepte maintenant
les URLs publiques **et** l'envoi direct d'un fichier en `multipart/form-data`.
Aucune clé API n'est nécessaire.

JoynixScribe l'utilise comme **secours**, uniquement après l'échec des
fournisseurs à clé. Deux modes sont disponibles dans Réglages → Latence :

- **Secours normal** : à l'arrêt de la dictée, si Gemini/Groq/Cloudflare échouent.
- **Streaming improvisé** : en mode streaming, chaque segment de parole peut être
  envoyé à Aperture quand les autres fournisseurs échouent.

L'API limite le fichier à environ 1 Mio. L'app découpe donc le WAV en tranches de
20 s (640 Kio max), envoie chaque tranche et recolle les textes. Le mode normal
peut donc prendre plus de temps sur une longue dictée ; le mode streaming reste
réactif car les segments sont courts.

Aperture ne demande aucune clé et n'est jamais utilisé en aperçu live pour éviter
les appels inutiles.

## Cloudflare Worker IA comme proxy

Un Worker peut proxifier les appels Workers AI et masquer le token côté serveur.
C'est utile pour une application distribuée à plusieurs utilisateurs, mais pour
un usage personnel la clé locale chiffrée est plus simple et évite un point de
défaillance. L'option reste disponible dans le code (voir `CloudflareClient`).

## Compilation

Prérequis : JDK 17, Android SDK (compileSdk 35), Gradle wrapper 8.11.1.

```bash
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/sdk
./gradlew assembleRelease --no-daemon --max-workers=2
```

Limites mémoire volontaires : `-Xmx1536m` Gradle, 768 Mo Kotlin, 2 workers, pas
de daemon. Conserve-les si tu compiles sur une machine modeste.

Pour signer, place un fichier `keystore.properties` à la racine (ignoré par Git) :

```
storeFile=/chemin/vers/le/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Application desktop (Windows)

Le client desktop vit dans `desktop/`. C'est une vraie application **Tauri v2**
(backend Rust + interface HTML/CSS/JS, sans bundler), compilée pour Windows en
`x86_64-pc-windows-gnu`.

### Fonctionnalités

- **Barre flottante compacte** : réduite, toujours au-dessus des autres fenêtres,
  déplaçable, avec forme d'onde et minuteur.
- **Icône de notification (tray)** : menu Afficher / Démarrer-arrêter / Quitter,
  fermeture discrète en arrière-plan.
- **Raccourci global `Ctrl+Alt+Espace`** : démarre et arrête la dictée depuis
  n'importe quelle application, même JoynixScribe en arrière-plan.
- **Insertion intelligente** : le texte est tapé dans la fenêtre où tu travaillais
  (pas dans JoynixScribe), ou copié dans le presse-papiers.
- **Quatre fournisseurs** : Gemini, Groq, Cloudflare Workers AI et Aperture
  (secours sans clé).
- **Streaming** : le texte s'écrit par blocs de ~3 s pendant que tu parles, avec
  fusion sans doublon.
- **Réglages** : langue, consigne du modèle, secours Aperture, insertion
  automatique, toujours au-dessus, démarrage avec Windows, thème clair/sombre,
  limite journalière de dictées.
- **Historique local** (300 dictées), recherche, copie, suppression.
- **Test de clé intégré** : vérifie une clé sans consommer de transcription.
- **Aucune capture d'écran** : seuls le micro et, si tu l'actives, le nom de
  l'application active sont utilisés.

Les réglages et l'historique sont stockés dans
`%APPDATA%\com.joynix.joynixscribe` — jamais dans le dossier d'installation.

### Compilation

```bash
cd desktop/src-tauri
cargo build --target x86_64-pc-windows-gnu --release
cd ..
makensis installer.nsi
```

L'installeur NSIS (`desktop/installer.nsi`) installe **par utilisateur**, sans
droits administrateur, dans `%LOCALAPPDATA%\Programs\JoynixScribe`, avec
raccourcis menu Démarrer et Bureau, option « démarrer avec Windows » et
désinstalleur (suppression optionnelle des données).

## Licence

Licence d'attribution JoynixScribe (JAL) v1.0 — voir `LICENSE`.

Toute réutilisation du code, des idées ou du concept doit créditer **Joynix** et
**JoynixScribe** avec un lien vers le dépôt :
https://gitlab.com/joynix28/joynixscribe
