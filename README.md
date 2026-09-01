# Minuteur & Chronomètre

Application Android native (Kotlin / Jetpack Compose) — chronomètre et minuteur paramétrable, avec mémoire des temps et journal des versions.

**Version actuelle : 1.4.0** · **Licence : MIT**

---

## Fonctionnalités

- **Chronomètre** : décompte croissant, cadran façon chronomètre mécanique avec repères de secondes
- **Minuteur** configurable (minutes / secondes) avec préréglages rapides (1, 3, 5, 10 min)
- **Pause / Reprise** et **Réinitialisation**
- **Écran maintenu allumé** automatiquement pendant que le chrono ou le minuteur tourne
- **Mémoire des temps** : enregistrer un temps affiché, le réutiliser d'un tap (recharge le chrono ou le minuteur avec cette valeur), le supprimer, tout effacer — conservé après fermeture de l'app
- **Vibration + bip sonore** à la fin d'un minuteur
- **Page Changelog** intégrée (bouton en haut à droite de l'écran)

## Prérequis

- Android Studio Quail (2) ou plus récent
- Kotlin (fourni avec le projet), Jetpack Compose
- **Android 7.0 (API 24) minimum** — testé sur Samsung Galaxy S8 (Android 9)
- Aucune dépendance externe hors des bibliothèques Compose/AndroidX standards

## Installation du projet

1. Créer un projet Android Studio **Empty Activity (Compose)**, Kotlin, Minimum SDK API 24
2. Remplacer `app/src/main/java/.../MainActivity.kt` par le fichier fourni (adapter la ligne `package` en tête de fichier au package réellement généré)
3. Ajouter dans `app/src/main/AndroidManifest.xml`, avant `<application>` :
   ```xml
   <uses-permission android:name="android.permission.VIBRATE" />
   ```
4. Icône de l'application : remplacer les fichiers `ic_launcher.png` / `ic_launcher_round.png` dans chaque dossier `res/mipmap-*` par ceux fournis, et supprimer le dossier `res/mipmap-anydpi-v26` (icône adaptative par défaut du template) pour que la nouvelle icône raster soit utilisée sur toutes les densités d'écran
5. Synchroniser Gradle puis lancer sur un appareil (câble USB + débogage USB activé, ou émulateur)

## Permissions utilisées

| Permission | Usage | Portée |
|---|---|---|
| `VIBRATE` | Retour haptique (boutons, fin de minuteur) | Locale, sans accès aux données personnelles |

**Aucune permission réseau, aucune permission de stockage externe, aucun accès aux contacts/localisation/caméra/micro n'est demandé.** L'application fonctionne entièrement hors-ligne.

## Stockage des données

Les temps enregistrés sont stockés localement dans les **SharedPreferences** de l'application (`minuteur_prefs`), en mode `MODE_PRIVATE` — c'est-à-dire strictement privées à l'application, inaccessibles aux autres applications du téléphone sans root. Rien n'est envoyé vers un serveur : il n'existe aucun appel réseau dans le code.

## Gestion des exceptions & robustesse

Un passage de durcissement a été effectué sur tous les points d'accès aux services système et au stockage, pour qu'une défaillance locale (matériel absent, stockage plein, préférences corrompues) dégrade la fonctionnalité concernée sans jamais faire planter l'application :

- **Lecture des temps enregistrés** : chaque entrée est parsée individuellement ; une entrée corrompue ou un mode inconnu est ignorée plutôt que de faire échouer tout le chargement. En cas d'échec global (préférences illisibles), l'application redémarre avec une liste vide plutôt que de crasher.
- **Écriture des temps enregistrés** : les échecs d'écriture (stockage plein, etc.) sont interceptés et ignorés silencieusement ; la session en cours n'est pas affectée.
- **Vibreur** : l'obtention du service `Vibrator` et chaque appel de vibration sont protégés — sur un appareil sans vibreur, ou si la permission est refusée par le système, l'application continue normalement sans vibrer.
- **Génération du bip sonore** (`ToneGenerator`) : sa création peut échouer sur certains appareils/émulateurs (ressources audio indisponibles) ; dans ce cas l'application continue sans bip plutôt que de planter.
- **Bornage des données** : un temps enregistré est plafonné à 24h à la lecture (rejette les valeurs aberrantes) ; le nombre d'entrées mémorisées est plafonné à 200, avec suppression des plus anciennes au-delà — empêche une croissance illimitée du stockage local.
- **Division par zéro évitée** sur le calcul de progression de l'anneau du minuteur lorsque la durée configurée est nulle.
- **Rechargement d'un temps enregistré** désactivé pendant qu'un chrono/minuteur est activement en cours, pour éviter d'écraser un décompte actif par inadvertance.

## Sécurité

L'application suit une logique de **surface d'exposition minimale** :

- Un seul composant exporté (`MainActivity`, l'écran de lancement — exposition obligatoire pour tout point d'entrée d'application). Aucun autre `service`, `broadcast receiver` ou `content provider` n'est déclaré, donc rien d'autre n'est accessible depuis l'extérieur de l'application.
- **Aucune permission réseau** (`INTERNET` n'est pas déclarée) : l'application ne peut techniquement effectuer aucune requête sortante, aucune fuite de données possible par ce biais.
- Les données stockées (temps enregistrés) sont non sensibles par nature (valeurs de durée uniquement, aucune donnée personnelle).
- Stockage en `MODE_PRIVATE` exclusivement (pas de mode `WORLD_READABLE`/`WORLD_WRITABLE`, obsolètes et non sécurisés).

### Durcissement optionnel — exclure les temps enregistrés des sauvegardes automatiques

Par défaut, le modèle Android Studio active `android:allowBackup="true"`, ce qui permet à Android de sauvegarder les données de l'app (dont les temps enregistrés) lors d'une sauvegarde cloud/ADB. Les données n'étant pas sensibles, ce n'est pas un risque en soi, mais si tu préfères les exclure explicitement, ajoute ceci dans `res/xml/data_extraction_rules.xml` et `res/xml/backup_rules.xml` (fichiers déjà générés par le wizard) :

```xml
<!-- data_extraction_rules.xml -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="minuteur_prefs.xml"/>
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="minuteur_prefs.xml"/>
    </device-transfer>
</data-extraction-rules>
```

```xml
<!-- backup_rules.xml -->
<full-backup-content>
    <exclude domain="sharedpref" path="minuteur_prefs.xml"/>
</full-backup-content>
```

## Structure du projet

```
app/src/main/
├── java/.../MainActivity.kt      # Toute la logique et l'UI (Compose, un seul fichier)
├── res/mipmap-*/                 # Icônes de l'application (toutes densités)
└── AndroidManifest.xml           # Déclaration de l'activité + permission vibreur
```

## Journal des versions (Changelog)

Consultable directement dans l'application via le bouton `v1.4.0` en haut à droite de l'écran.

| Version | Contenu |
|---|---|
| 1.4.0 | Page Changelog intégrée à l'application |
| 1.3.0 | Fonction Mémoire (enregistrer/recharger/supprimer des temps, persistance locale), écran maintenu allumé, nouvelle icône |
| 1.2.0 | Icônes play/pause/reset redessinées en vectoriel (fiabilité multi-appareils) |
| 1.1.0 | Optimisations internes du code Compose |
| 1.0.0 | Version initiale : chronomètre, minuteur configurable, pause/reprise/réinitialisation |

## Licence

Ce projet est distribué sous licence **MIT** — voir le fichier [`LICENSE`](LICENSE). En résumé : libre d'utilisation, de modification, de distribution et d'usage commercial, sans garantie, à condition de conserver la mention de copyright et la licence dans les copies.

## Limitations connues

- Testé principalement sur Samsung Galaxy S8 (Android 9) ; le comportement sur d'autres constructeurs (gestion agressive de la batterie type Xiaomi/Huawei) n'a pas été validé — l'app pourrait être mise en veille par le système si l'optimisation de batterie n'est pas désactivée manuellement pour elle.
- Le minuteur ne continue pas en tâche de fond si l'application est fermée (swipe depuis les apps récentes) ; il fonctionne uniquement premier plan/écran allumé, conformément à l'usage prévu.
- APK généré en version *debug* par défaut ; pour une distribution plus large (Play Store, partage à des tiers), une signature en version *release* serait nécessaire.
