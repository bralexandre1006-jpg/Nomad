Règles universelles — Vibecoding d'applications Android
Collaboration

    Je décris les fonctionnalités en français ; prends en charge les aspects techniques et réponds brièvement en français.
    Commence par comprendre la demande et les fichiers concernés. Ne relis pas tout le dépôt à chaque tâche.
    Préfère des modifications ciblées et réutilise les composants existants. Évite les sous-agents et les opérations coûteuses sans bénéfice clair.
    Pour les décisions importantes ou ambiguës, propose brièvement une solution et demande confirmation.

Sécurité des mises à jour Android

    Pour une application existante, ne change jamais applicationId/identifiant du package, clé de signature, certificat ou keystore sans mon accord explicite.
    Vérifie le mécanisme de signature existant avant de produire une mise à jour. N'invente pas une nouvelle clé pour remplacer une clé indisponible : signale le problème.
    Pour une nouvelle application, configure un identifiant unique et une clé de signature pérenne, distincte si nécessaire des autres applications. Explique comment conserver la clé et ses mots de passe en sécurité.
    Incrémente versionCode pour toute mise à jour distribuée ; ajuste versionName selon la convention du projet.
    Préserve les données locales, les migrations et la compatibilité des mises à jour. Ne désinstalle pas l'application pour résoudre un problème de signature.
    Ne publie jamais de keystore, mots de passe, jetons ou secrets dans le dépôt, les logs ou les réponses. Utilise des secrets GitHub Actions quand c'est approprié.

GitHub et compilation

    Respecte le workflow GitHub Actions existant pour compiler l'APK. Ne le modifie pas sans nécessité expliquée.
    Avant un push, résume les fichiers modifiés et demande mon autorisation explicite.
    Vérifie les tests pertinents si possible. En cas d'échec de compilation, analyse d'abord les lignes d'erreur utiles et corrige la cause plutôt que relancer en boucle.
    Ne supprime ni historique Git, ni branche, ni données sans mon accord.

Livraison de l’APK — lien direct prioritaire

    Après chaque compilation GitHub Actions réussie, identifie l’APK final et donne-moi en priorité un lien de téléchargement direct vers le fichier APK, et non uniquement le lien de la page du dépôt ou du run GitHub Actions.
    Si l’APK est publié dans une GitHub Release, fournis l’URL directe de l’asset .apk (lien le plus pratique). Ne crée pas de Release et ne publie rien sans mon autorisation explicite.
    Si l’APK est uniquement disponible comme artifact GitHub Actions, tente d’obtenir un lien de téléchargement fonctionnel et explique brièvement si une connexion GitHub est requise ou si le lien est temporaire. Un artifact Actions est généralement une archive ZIP contenant l’APK : ne prétends pas que le lien télécharge directement un APK si ce n’est pas le cas.
    Si aucun lien direct utilisable n’est disponible, indique clairement la limite et donne le lien le plus précis possible vers l’artifact du run, plutôt qu’un lien générique vers GitHub.
    Ne fabrique jamais d’URL et vérifie que la compilation a réussi et que le fichier existe avant d’annoncer sa disponibilité.
    Dans le compte rendu final, affiche en premier Télécharger l’APK : [lien] quand un lien valide est disponible, puis la version et un bref résumé.

Design et expérience utilisateur

    Vise une interface cohérente, soignée, moderne, accessible et fluide, adaptée à Android priorité à la compatibilités du Xiaomi 17T Pro pour que l'interface s'adapte à la caméra ect..
    Respecte la direction artistique et les composants déjà établis ; demande des références si une refonte majeure est ambiguë.
    Prête attention à la typographie, aux espacements, aux états de chargement/erreur, à la navigation et aux animations raisonnables.
    Ne refonds pas des écrans non concernés par ma demande.

Économie de quota

    Cherche et lis uniquement les fichiers utiles ; évite les analyses complètes répétées.
    Regroupe les modifications liées, évite les explications longues et donne un compte rendu concis.
    Ne déclenche pas de compilations multiples, d'agents parallèles ou de recherches externes sans raison.
    Ces règles ne changent pas automatiquement le modèle Claude utilisé.

À personnaliser pour chaque dépôt

Lors de la première session, complète un petit encadré Informations du projet avec : nom, stack, applicationId, emplacement du workflow GitHub Actions, méthode de signature (sans secrets), conventions de version et emplacement des données locales. Vérifie ces informations dans le dépôt ; ne les devine pas.

 Optimisation du développement et des tokens

- Privilégie les modifications minimales, précises et ciblées.
- Réutilise au maximum les composants, fonctions et dépendances déjà présents.
- Évite de lire intégralement les fichiers lorsqu'une recherche ciblée suffit.
- Ne relis pas inutilement des fichiers déjà analysés si leur contenu n'a pas changé.
- Limite les sous-agents, les commandes répétitives et les analyses superflues.
- Évite les refactorisations non demandées et la création de fichiers inutiles.
- Privilégie les solutions simples, efficaces, maintenables et performantes.
- Effectue uniquement les tests nécessaires pour vérifier les modifications, sans négliger les contrôles importants.
- Ne sacrifie jamais la qualité du design, les animations, l'expérience utilisateur ou les fonctionnalités pour économiser des tokens.
- Préserve impérativement les données utilisateur, la signature APK, le keystore, l'applicationId et les workflows GitHub Actions.
- Ne supprime ni ne modifie aucune fonctionnalité existante sans nécessité ou demande explicite.
- Fournis des réponses concises en français, sans explications répétitives ni comptes rendus excessivement longs.
- Applique ces principes automatiquement à chaque intervention, sans avoir besoin de me les rappeler.
## Informations du projet

- Nom : Nomad
- Stack : Capacitor 8 + HTML/CSS/JS vanilla (`www/`), d3-geo + topojson (globe/carte), flag-icons
- applicationId : `com.nomad.globe`
- Workflow GitHub Actions : `.github/workflows/android.yml` (artifact `Nomad-vX.Y.Z`)
- Signature : keystore PKCS12 (alias `nomad`) via secrets `NOMAD_KEYSTORE_BASE64`, `NOMAD_KEYSTORE_PASSWORD`, `NOMAD_KEY_ALIAS`, `NOMAD_KEY_PASSWORD` ; config dans `android/app/build.gradle`
- Version : `versionCode` (+1 à chaque livraison) et `versionName` semver dans `android/app/build.gradle`
- Données locales : `localStorage` clé `nomad.v1` (pays visités, années `years`, villes `cities`, réglages) ; IndexedDB `nomad` / store `photos` (métadonnées) + fichiers photos dans `Directory.DATA/photos/` (Filesystem)
- Généré au build (non versionné) : `www/vendor`, `www/flags`, `www/data` (dont `data/cities/XX.json`, GeoNames via all-the-cities) via `npm run build`
