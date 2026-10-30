# JoynixScribe

Dictée vocale universelle pour Android 12+ (cible Android 15+). Une bulle flottante,
un tap pour parler, un tap pour arrêter : le texte est transcrit par IA puis inséré
dans le champ actif de n'importe quelle application, sans changer de clavier.

Open source, sans analytics, sans serveur intermédiaire. L'audio ne quitte l'appareil
que pour être envoyé au fournisseur d'IA choisi (Gemini, Groq ou Cloudflare Workers AI).

## Fonctionnalités

- Bulle flottante personnalisable : couleur, icône, taille, opacité, geste (tap ou
  maintenir pour parler puis relâcher pour envoyer).
- Trois fournisseurs : Google Gemini, Groq Whisper, Cloudflare Workers AI.
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

L'endpoint `https://aperture-sciences.com/api/audio/transcribe` n'accepte que des
URLs Discord CDN (`cdn.discordapp.com`, `media.discordapp.net`,
`cdn.discordapp.net`) et refuse tout fichier audio local. Il n'est donc pas
utilisable comme secours pour une dictée locale et n'est pas intégré.

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

## Licence

Licence d'attribution JoynixScribe (JAL) v1.0 — voir `LICENSE`.

Toute réutilisation du code, des idées ou du concept doit créditer **Joynix** et
**JoynixScribe** avec un lien vers le dépôt :
https://gitlab.com/joynix28/joynixscribe
