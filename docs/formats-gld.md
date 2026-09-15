# Formats de données Breathless

Rétro-ingénierie des formats à partir des sources d'origine. Les références de lignes
pointent vers le dépôt `Breathless` (Fields of Vision, 1995-96).

Tout est **big-endian** (68000). Les types suivent les structures C de l'éditeur :
`WORD` = 16 bits, `LONG` = 32 bits, signés sauf mention contraire.

> État de validation : les 25 fichiers du jeu s'ouvrent, les 20 niveaux se décompressent
> à la taille exacte annoncée. Voir `gld-core/src/test/java/fov/breathless/gld/OriginalDataIT.java`.

---

## 1. Conteneur VDCO (fichier compressé)

Source : `Sorgenti/FileAccess.asm`, `OpenCustom` (l. 36-120).

| Offset | Taille | Champ |
|---|---|---|
| 0 | 4 | ID `VDCO` — *Virtual Dreams COmpression* |
| 4 | 4 | taille après décompression |
| 8 | 4 | taille des données compressées (**hors** octet de mode) |
| 12 | 1 | mode de compression : 0 = normal, 1 = graphique |
| 13 | n | données compressées |

Un fichier sans cet ID n'est pas compressé : son contenu commence directement par
l'ID de charge utile (`MGLD`, `LGLD`, `TGLD`, `OGLD`, `GGLD`, `SGLD`).

Dans le jeu complet : les 20 niveaux sont compressés (ratio ~5 à 6,5), les quatre
banques de ressources partagées et le fichier maître ne le sont pas.

## 2. Compression SLZ

Source : `Sorgenti/FileAccess.asm`, `UnPack0` (l. 168-247) et `UnPack1` (l. 251-340).
Portage : `gld-core/src/main/java/fov/breathless/gld/Slz.java`.

LZ77 à fenêtre glissante piloté par des octets TAG :

- **TAG == 0** : 8 octets littéraux suivent, puis un nouveau TAG.
- **TAG != 0** : ses 8 bits sont lus du plus significatif au moins significatif.
  - bit à 0 : un octet littéral ;
  - bit à 1 : une référence arrière, codée sur deux octets `cs` puis `lo` :
    - `cs == 0` termine le flux ;
    - `longueur = (cs & MASK) + 2` ;
    - `distance = ((cs << LSL) & 0xFF00) | lo`.

| Mode | MASK | LSL | Longueurs | Fenêtre |
|---|---|---|---|---|
| 0 « normal » | `0x0F` | 4 | 2..17 | 4096 |
| 1 « graphique » | `0x1F` | 3 | 2..33 | 2048 |

La copie se fait **octet par octet** et peut chevaucher la zone en cours d'écriture
(c'est ainsi que se codent les répétitions de motif) : ne jamais la remplacer par une
copie de bloc.

Seul le mode 0 apparaît dans les données livrées ; le mode 1 est implémenté pour
complétude.

## 3. LGLD — un niveau

Source de référence : `MapEditor/GLDAccess.c`, `WriteMapGLD` (l. 1031-1210), qui définit
la disposition exacte des octets ; `ReadMapGLD` (l. 23-400) confirme l'ordre de lecture.

| Offset | Taille | Champ |
|---|---|---|
| 0 | 4 | ID `LGLD` |
| 4 | 2 | nombre de niveaux dans le fichier (toujours 1) |
| 6 | 4 | offset de la directory (toujours 0) |
| 10 | 1 | drapeau de compression interne (toujours 0) |
| 11 | 4 | longueur de la carte : taille du bloc « blocs -> carte » ci-dessous |
| 15 | ... | début de la zone comptée par le champ précédent |

Puis, séquentiellement :

```
LONG   nbBlocs
Bloc[nbBlocs]          32 octets chacun
LONG   nbAretes
Arete[nbAretes]        16 octets chacun
Effets                 listes de records de 10 octets (section 3.4)
Carte                  128 x 128 WORD = 32768 octets
-- fin de la zone comptée par « longueur de la carte » --
LONG   nbTextures
char[8] x nbTextures   noms des textures utilisées par le niveau
LONG   nbObjets
char[4] x nbObjets     noms des objets utilisés par le niveau
LONG   nbObjetsEnCarte
ObjetCarte[...]        8 octets chacun (section 3.6)
LONG   nbSons
char[4] x nbSons       noms des sons utilisés par le niveau
char[4]                nom de l'image de chargement
```

Les noms sont des champs de longueur fixe complétés par des espaces.

### 3.1 Bloc (32 octets)

Identique à la structure runtime `bl_*` de `Sorgenti/TMap.i` : le fichier est chargé tel
quel en mémoire, seuls les index sont convertis en pointeurs.

| Offset | Type | Champ |
|---|---|---|
| 0 | WORD | hauteur du sol |
| 2 | WORD | hauteur du plafond |
| 4 | WORD | index de texture du sol |
| 6 | WORD | index de texture du plafond — **négatif si le plafond est le ciel** |
| 8 | WORD | numéro de bloc (remis à 0 à l'écriture) |
| 10 | WORD | éclairage : octet haut = intensité ; bit 7 de l'octet bas = brouillard |
| 12 | LONG | index de l'arête 1 (est) |
| 16 | LONG | index de l'arête 2 (sud) |
| 20 | LONG | index de l'arête 3 (ouest) |
| 24 | LONG | index de l'arête 4 (nord) |
| 28 | BYTE | numéro de liste d'effets déclenchée en entrant sur ce bloc (0 = aucune) |
| 29 | BYTE | trigger secondaire subi par ce bloc |
| 30 | BYTE | attributs (section 3.2) |
| 31 | BYTE | trigger principal subi par ce bloc |

Disposition des arêtes, vue de dessus :

```
          arête 4
        +---------+
        |         |
arête 3 |         | arête 1
        |         |
        +---------+
          arête 2
```

### 3.2 Attributs de bloc

| Bit | Signification |
|---|---|
| 0-1 | type de sol : 0 = normal, 1 = -2 santé, 2 = -5 santé, 3 = -10 santé |
| 2 | inutilisé |
| 3 | les ennemis ne peuvent pas entrer sur ce bloc |
| 4 | l'arête 1 est un interrupteur (activable à la barre d'espace) |
| 5 | l'arête 2 est un interrupteur |
| 6 | l'arête 3 est un interrupteur |
| 7 | l'arête 4 est un interrupteur |

### 3.3 Arête (16 octets)

| Offset | Type | Champ |
|---|---|---|
| 0 | LONG | index de la texture principale |
| 4 | LONG | index de la texture haute |
| 8 | LONG | index de la texture basse |
| 12 | WORD | attributs : bit 0 = texture haute *unpegged*, bit 1 = texture basse *unpegged* |
| 14 | WORD | inutilisé |

Le vocabulaire *upper / lower / unpegged* est repris de Doom : les textures haute et
basse habillent les dénivelés entre deux blocs de hauteurs différentes.

### 3.4 Effets

Une suite de **listes** d'effets. Chaque enregistrement fait 10 octets :

| Offset | Type | Champ |
|---|---|---|
| 0 | WORD | numéro de trigger |
| 2 | WORD | code de l'effet |
| 4 | WORD | paramètre 1 |
| 6 | WORD | paramètre 2 |
| 8 | BYTE | clé requise pour déclencher l'effet (0 = aucune) |
| 9 | BYTE | inutilisé |

Une liste se termine par un `LONG` à 0 (qui occupe la place du couple trigger/effet du
record suivant). L'ensemble des listes se termine par un `LONG` à -1.

Les blocs référencent une liste par son numéro d'ordre (champ *liste d'effets*, 1-based)
et subissent un effet via leur *trigger* : tous les blocs portant le même numéro de
trigger sont affectés ensemble. Le catalogue des effets (portes, ascenseurs, sols et
plafonds mobiles...) est dans `MapEditor/FxList.h`.

### 3.5 Carte

128 x 128 `WORD`, en lignes (index = `y * 128 + x`).

- `0` : case vide, hors du monde jouable ;
- `> 0` : index (1-based) du bloc traversable occupant la case ;
- `< 0` : idem, mais **le lancer de rayons s'arrête ici** — c'est un mur plein.

Le signe est donc le drapeau de solidité, testé directement par le raycaster
(`bmi` dans `Sorgenti/3d.asm`). En mémoire, le moteur d'origine double la carte en
4 octets par case : le `WORD` du bloc suivi d'un `WORD` portant le numéro de l'objet
présent sur la case.

### 3.6 Objet placé en carte (10 octets)

| Offset | Type | Champ |
|---|---|---|
| 0 | WORD | index dans la table des objets du niveau (**0 = position de départ du joueur**) |
| 2 | WORD | x monde |
| 4 | WORD | z monde |
| 6 | WORD | cap — l'éditeur l'écrit décalé de 8 bits (`cap << 8`) |
| 8 | BYTE | drapeaux (toujours 0 dans les données livrées) |
| 9 | BYTE | trigger d'activation |

Taille confirmée des deux côtés : `sizeof(struct FMapObject)` côté éditeur
(`GLDAccess.c` l. 1184) et `mulu.w #10,d0` côté moteur (`Loader.asm` l. 1309).

Deux particularités reprises du moteur d'origine (`Loader.asm` l. 1316-1334) :

- l'entrée d'index **0** n'est pas un objet mais la **position de départ du joueur** ;
  elle est retirée du décompte et alimente `CPlayerX` / `CPlayerZ` / `CPlayerHeading` ;
- le dernier octet est copié dans `obj_inactive` : un ennemi portant un trigger non nul
  reste **endormi** jusqu'à ce que l'effet correspondant se déclenche.

## 4. Autres conteneurs

| ID | Fichier livré | Contenu |
|---|---|---|
| `MGLD` | `BREATHLESS.GLD` | fichier maître : liste des parties et des niveaux |
| `TGLD` | `BLES0001.GLD` | banque de textures (300 max, 60 par niveau) |
| `GGLD` | `BLES0002.GLD` | images plein écran (chargement, panneau...) |
| `OGLD` | `BLES0003.GLD` | objets et ennemis : sprites et paramètres de comportement |
| `SGLD` | `BLES0004.GLD` | échantillons sonores et modules ProTracker |
| `LGLD` | `BLES0006..0025.GLD` | les 20 niveaux |

Chacun suit le même principe : en-tête, données, puis une directory d'entrées
`(nom, offset, longueur)` repérée par l'offset inscrit dans l'en-tête.

### 4.1 TGLD — banque de textures

```
+0    'TGLD'
+4    LONG   offset de la directory
+8    enregistrements de texture, concaténés
dirOffset :
      LONG   nombre de textures
      entrées de 16 octets : nom sur 8 caractères, offset, longueur
```

Un enregistrement de texture (structure `FTexture` de l'éditeur) :

| Offset | Type | Champ |
|---|---|---|
| 0 | WORD | largeur, en colonnes |
| 2 | WORD | nombre d'images de l'animation |
| 4 | WORD | hauteur, en lignes |
| 6 | WORD | exposant tel que `1 << exposant == hauteur` |
| 8 | LONG | offset de la première image |
| 12 | LONG | offset de la variante d'interrupteur, 0 s'il n'y en a pas |
| 16 | LONG[] | liste d'offsets terminée par zéro, si la texture est animée |

**Piège** : tous ces offsets sont relatifs au champ *hauteur*, donc à l'octet 4 de
l'enregistrement, et non à son début. C'est une conséquence directe du moteur, dont le
pointeur de texture vise ce champ (offsets `tx_*` dans `Sorgenti/TMap.i`). L'offset de
variante d'interrupteur désigne lui aussi le champ *hauteur* de l'enregistrement
imbriqué, dont le début est donc quatre octets plus haut — les deux décalages ne
s'annulent pas.

Les pixels sont rangés **par colonnes** : la colonne `x` occupe les `hauteur` octets
consécutifs à partir de `x * hauteur`. C'est ce qui permet au raycaster de tracer un
trait vertical en parcourant des octets contigus, et la raison d'être du champ exposant
(la multiplication par la hauteur se fait par décalage). Chaque octet est un index dans
la palette.

Dimensions observées dans les données livrées : 64×64 (65 textures), 64×128 (19),
64×32 (8), 64×16 (1) et 256×128 (4). Les brosses de 256 de large sont les ciels
(`SKY_BRUSH_WIDTH`).

### 4.2 GGLD — banque graphique, palettes et éclairage

```
+0    'GGLD'
+4    LONG   offset de la directory
+8    WORD   nombre de palettes
      palettes, 768 octets chacune (256 triplets RVB)
      WORD   nombre de tables d'éclairage
      tables d'éclairage, 8192 octets chacune
      données des images plein écran
dirOffset :
      LONG   nombre d'images
      entrées de 12 octets : nom sur 4 caractères, offset, longueur
```

Le jeu livre **deux palettes** (normale et flash rouge des dégâts), **deux tables
d'éclairage** (normale et brouillard) et 10 images plein écran.

Une table d'éclairage fait 32 niveaux × 256 index. C'est le mécanisme d'éclairage
intégral du moteur : plutôt que de calculer une couleur, le rendu remplace chaque index
de palette par un autre, pris dans la ligne du niveau de lumière voulu. Le **niveau 0
est la pleine lumière** et le niveau 31 le quasi-noir ; la luminance moyenne décroît
linéairement (95 → 2 sur les données d'origine).

### 4.3 OGLD — banque d'objets

```
+0    'OGLD'
+4    LONG   offset de la directory
+8    enregistrements d'objet, concaténés
dirOffset :
      LONG   nombre d'objets
      entrées de 12 octets : nom sur 4 caractères, offset, longueur
```

Un enregistrement d'objet :

| Offset | Type | Champ |
|---|---|---|
| 0 | WORD | nombre d'images |
| 2 | WORD | rayon, en unités monde (collisions) |
| 4 | WORD | hauteur, en unités monde (collisions) |
| 6 | BYTE | type d'animation : −1 directionnelle, 0 aucune, 1 simple |
| 7 | BYTE | type d'objet : 0 décor, 1 joueur, 2 ennemi, 3 ramassable, 4 projectile, 5 explosion |
| 8 | WORD | paramètres 1 à 4 |
| 16 | BYTE | paramètres 5 à 12 |
| 24 | LONG | noms des trois sons, sur 4 caractères |
| 36 | LONG | offset de l'image courante |
| 40 | LONG | inutilisé |
| 44 | LONG[] | liste d'offsets d'images, terminée par zéro |

Le sens des douze paramètres dépend du type d'objet : points de vie, dégâts, portées,
vitesses.

Une **image de sprite** :

```
+0    WORD     largeur
+2    WORD     hauteur
+4    WORD     décalage horizontal du dessin par rapport à l'ancrage
+6    WORD     décalage vertical
+8    LONG[]   un offset de colonne par colonne
```

Chaque colonne est une suite de **segments verticaux** : un octet de ligne de départ, un
octet de longueur, puis les index de palette. Un octet de départ ≥ 0x80 termine la
colonne. Ce qui n'appartient à aucun segment est transparent — c'est le principe des
« posts » de Doom, et c'est ce qui permet un sprite découpé sans couleur réservée.

**Deux pièges d'offsets**, différents de ceux des textures : les offsets d'images sont
relatifs au **début de l'enregistrement d'objet**, ceux des colonnes au **début de leur
image**. Le moteur les convertit en pointeurs absolus au chargement (`InitObjects` dans
`TMapMain.asm`), ce qui explique qu'il les déréférence ensuite directement.

Contenu livré : 43 objets — 12 ennemis, 12 ramassables, 11 projectiles, 4 explosions,
4 éléments de décor. Chaque ennemi porte **130 images** : 8 orientations × 16 poses, plus
deux images d'état. L'orientation affichée se déduit de l'écart entre le cap de l'ennemi
et celui du joueur, ce qui permet de le voir de dos quand il s'éloigne.

Quelques sprites d'origine ont un segment qui dépasse d'une ligne la hauteur déclarée.
Le moteur ne vérifiait pas cette borne — il découpait à l'écran, pas au sprite — et le
parseur conserve donc les deux valeurs plutôt que d'en corriger une.

### 4.4 SGLD

Non encore implémenté. Même structure, pour les échantillons et les modules ProTracker.
