# Synchro

Synchro garde des dossiers identiques entre ton téléphone Android et tes autres appareils :
ordinateur, tablette, autre téléphone. Les photos prises sur le téléphone arrivent sur l'ordinateur,
un document modifié sur l'ordinateur se retrouve sur le téléphone.

Les fichiers passent directement d'un appareil à l'autre, chiffrés, sans compte ni stockage en
ligne. Synchro repose sur [Syncthing](https://syncthing.net), logiciel libre de synchronisation, qu'il
faut aussi installer sur les autres appareils.

<p align="center">
  <img src="docs/statut.png" width="220" alt="Onglet Statut : mode d'exécution et raisons pour lesquelles la synchronisation tourne">
  <img src="docs/tiroir.png" width="220" alt="Menu : identifiant de l'appareil, changements récents, interface web, import et export">
  <img src="docs/reglages.png" width="220" alt="Écran des réglages">
</p>

## Installer

1. Installer [Obtainium](https://github.com/ImranR98/Obtainium) sur le téléphone.
2. Y ajouter une application avec l'adresse `https://github.com/kvngch/syncthing-android`.
3. Choisir l'APK qui se termine par `arm64-v8a`, qui convient à presque tous les téléphones récents.

Obtainium installe ensuite les mises à jour. Sans Obtainium, l'APK se télécharge depuis la
[dernière version](https://github.com/kvngch/syncthing-android/releases/latest).

Au premier lancement, Synchro demande l'accès aux fichiers, l'envoi de notifications et l'exemption
d'optimisation de la batterie, sans laquelle Android arrête la synchronisation en arrière-plan.

## Ajouter un appareil

Chaque appareil a un identifiant, affiché en code QR dans le menu de Synchro. Pour relier deux
appareils, on scanne le code de l'autre avec le bouton d'ajout de l'onglet Appareils, puis on choisit
les dossiers à partager avec lui.

## Choisir quand synchroniser

Par défaut, Synchro synchronise en Wi-Fi seulement. Les conditions se règlent dans
Réglages, Conditions d'exécution :
- Wi-Fi, ou seulement certains réseaux Wi-Fi ;
- données mobiles, itinérance ;
- en charge seulement, ou hors économiseur d'énergie ;
- quelques minutes toutes les heures, pour économiser la batterie.

Chaque dossier et chaque appareil peut avoir ses propres conditions : les photos en Wi-Fi seulement,
les documents aussi sur données mobiles. L'onglet Statut indique si la synchronisation tourne et
pourquoi.

En haut de l'onglet Statut, trois modes :
- **Suivre les conditions d'exécution** : le mode normal, qui applique les réglages ci-dessus ;
- **Démarrage forcé** : synchronise tout de suite, quelles que soient les conditions, jusqu'au retour
  au mode normal ;
- **Arrêt forcé** : suspend la synchronisation.

## Autres fonctions

- Changements récents : les derniers fichiers reçus ou envoyés, à ouvrir d'un appui.
- Partage : un fichier d'une autre application s'envoie dans un dossier synchronisé.
- Appareil photo : une photo prise depuis la tuile de réglages rapides va directement dans un dossier.
- Tuiles de réglages rapides pour forcer ou planifier la synchronisation.
- Sauvegarde et restauration de toute la configuration, chiffrée par mot de passe si besoin.
- Thème clair ou sombre, interface traduite dans une trentaine de langues.

## Données personnelles

Synchro ne collecte rien et n'envoie rien ailleurs qu'aux appareils choisis. La localisation ne sert
qu'à lire le nom du réseau Wi-Fi, pour la condition « certains réseaux Wi-Fi ». Elle est proposée au
premier lancement et peut être refusée.

## Pour les développeurs

Application en Kotlin et Jetpack Compose, `minSdk` 26 (Android 8). Elle lance le binaire officiel de
Syncthing (v2.1.7-rc.2, en sous-module) et le pilote par son API REST locale. Le code vient de
Syncthing-Fork, dérivé de l'application officielle
[syncthing-android](https://github.com/syncthing/syncthing-android), aujourd'hui arrêtée.

```bash
git clone --recursive https://github.com/kvngch/syncthing-android.git
cd syncthing-android
python3 scripts/install_minimum_android_sdk_prerequisites.py
export ANDROID_HOME="$PWD/../syncthing-android-prereq"
./gradlew buildNative
./gradlew lintDebug testDebugUnitTest assembleDebug
```

Le build demande JDK 21, Go et Python 3. Une version publiée se crée en lançant le workflow
Release App avec un tag ; les APK sont signés par la clé de Synchro, d'empreinte SHA-256
`F7:F2:FA:72:32:67:71:BD:24:49:88:C4:F7:EF:09:EE:0F:2D:69:57:C9:8D:EB:EA:78:3E:7F:C1:24:C1:05:78`.

## Licence

[MPL-2.0](LICENSE). Merci aux mainteneurs successifs du code d'origine :
[nutomic](https://github.com/nutomic), [imsodin](https://github.com/imsodin) et
[Catfriend1](https://github.com/Catfriend1).
