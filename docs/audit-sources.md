# Audit du portage contre les sources 68k — 22 novembre 2026

Comparaison de `Breathless/Sorgenti/*.asm` avec le portage. Chaque point cite les deux
côtés ; ceux marqués ✔ ont été revérifiés à la main. Les écarts déjà assumés dans le
README ne figurent pas ici.

Légende : 🔴 bug visible ou audible · 🟠 manque · ⚪ écart mineur

## État au 22 novembre 2026

Tout ce qui suit est corrigé, sauf les points de la liste ci-dessous, gardés comme
écarts assumés (voir le README) ou laissés de côté :

- **Erreur de l'audit** : le déclenchement « à chaque pas » (§1) était faux. `PlayerMoved`
  n'est levé qu'au **changement de bloc** (`movement.asm`, `st PlayerMoved` après
  `cmp.w CPlayerBlock`) ; c'est ce que fait le portage.
- Cadence des textures animées : 6,25 i/s fixes (l'original dépendait de la machine).
- Souris, filtre audio, touches par défaut, pixels 1×1 au départ, police de l'écran de
  chargement, boucle d'échantillon entière : écarts assumés.
- Anti-répétition du son d'impact (bits 3 et 7 du sous-type) : non porté.
- Jonction plafond/mur à une ligne près (§4, confiance moyenne) : non modifiée, à vérifier
  à l'œil contre l'original.
- Avant et arrière tenus ensemble : l'original lit hors de sa table, comportement
  indéfini ; le portage reste immobile.

## 1. Effets de blocs (`Animations.asm`, `movement.asm`)

- 🔴 ✔ **Les effets définitifs se rejouent.** `StopEffect2` (statut -2) ne réarme pas le
  trigger, `StopEffect1` (-1) si (`Animations.asm:128-137`). `Effects.stop()`
  (`Effects.java:698`) réarme toujours. 258 entrées concernées, dont 43 en marchant :
  ex. BLES0007 liste 12, CeilDown 208 rejoué à chaque passage → plafond sous le sol.
- 🔴 ✔ **Les clés ne sont jamais exigées.** `movement.asm:1250-1283` : sans la clé,
  effet annulé, message `clé+3`, reste de la liste abandonné ; avec, la clé est consommée.
  `EffectEntry.key()` n'est lu nulle part. 48 entrées à clé.
- 🔴 Lumière liée à une porte qui reste ouverte : l'original garde l'éclairage si l'effet
  suivi finit en -2 (`Animations.asm:773-779`) ; `Effects.java:235-242` restaure toujours.
- 🔴 Porte qui se referme sur le joueur : l'original la rouvre entièrement et refait la
  pause (`Animations.asm:336-346`) ; le Java la laisse en suspens (`Effects.java:522-529`).
- 🔴 Ennemis et restes sur une porte : bit 15 du mot de commande qui bloque la porte
  (`Objects.asm:673-678`, `Animations.asm:280-281, 328-329`) et `RemoveRemains` à la
  fermeture — absents.
- 🟠 Téléport sans fondu (`TransEffect`) ni gel du joueur et des ennemis
  (`Animations.asm:795`, `movement.asm:94`, `Objects.asm:1846`).
- 🟠 Sons de porte : arrêt (`GLOBAL_DOOR_STOP`) jamais joué, fermeture muette
  (`Effects.java:507`).
- ⚪ Déclenchement en marchant : l'original relance la liste à chaque image de mouvement
  hors chute (`movement.asm:544-561`), le Java au seul changement de case.
- ⚪ Clic d'interrupteur joué même quand rien ne part (`Effects.java:338` contre
  `movement.asm:1267`).
- ⚪ Cadence des textures animées dépendante de l'affichage dans l'original
  (`Animations.asm:43-90`), fixe à 6,25 i/s ici.
- ⚪ LightUp/LightDown inversés (`Effects.java:457, 573`) — latent, aucun niveau ne les emploie.
- ⚪ LinkedLight sur effet de course nulle : arrêt définitif dans l'original
  (`Animations.asm:740`), attente infinie ici.

## 2. Joueur et partie (`movement.asm`, `TMapMain.asm`, `Scores.asm`)

- 🔴 ✔ **Sol nocif** : première morsure après 50 pas dans l'original (`HurtTimer` à -1 →
  `DMinithurt`, `movement.asm:523-538`) ; immédiate dans `PlayerState.java:199-203`.
- 🔴 **Le joueur traverse ennemis et décors** : `CtrlCollPlayerObj`
  (`movement.asm:877-998`) recule le joueur et ajoute `AMovingDir = ±512` pour contourner.
  Absent.
- 🔴 ✔ **Carte automatique** : lit les hauteurs du fichier (`AutoMap.java:148-154`), pas
  celles du monde vivant — une porte ouverte arrête toujours le relevé.
- 🔴 Voile rouge et `hurtTimer` non remis à zéro au début de niveau (`InitScores2` fait
  `clr.w RedScreenCont`).
- 🔴 GAME OVER à chaque mort (`BreathlessApp.java:318-328`) ; l'original ne l'affiche
  qu'à court de tentatives (`TMapMain.asm:656-663`) et anime la mort (yeux qui descendent,
  `PostDeathWait` 60 pas).
- 🟠 Chute : vitesse verticale, accélération `$8000`, dénivelé ≥ 24, dégâts
  `(h>>7)<<2` au-delà de 256, pas d'effet au bloc d'atterrissage (`movement.asm:198-235,
  503-513, 601-611`). Ici Y est recalé d'un coup.
- 🟠 Regard haut/bas `LookHeight` (±72, inertie, touche de remise à zéro) : jamais branché,
  ciel compris (`DrawScreen.asm:225-250`).
- 🟠 Balancement de marche `PlayerYOsc` et bruit de pas `GlobalSound5`.
- 🟠 Ramassage sans son (`o_sound1`) ni message.
- 🟠 Touches de pas de côté dédiées (`sideleft`/`sideright`).
- ⚪ Ramassables en attente pris dès le départ dans l'original (`CtrlCollObj` ignore
  `obj_inactive`) ; `ActiveEnemy` ne réveille que `ObjEnemies`.
- ⚪ Liste du bloc de départ lancée au premier pas (`InitPlayerPos` : `st PlayerMoved`).
- ⚪ Fraction de départ `$3795` (`movement.asm:1043`) ; gauche prioritaire sur droite ;
  avant+arrière = avance ; `SwitchManagement` avant le déplacement.

## 3. Ennemis et combat (`Objects.asm`)

- 🔴 ✔ **Décisions à chaque pas** : `ChooseEnemyDir` n'est appelé que quand `obj_cont1`
  atteint 0 (`Objects.asm:657-660` ; 15 pas en poursuite/errance, 3 en visée, 47 après un
  mur, 10 au tir). `EnemyAi.java:157` décide à chaque pas.
- 🔴 ✔ **Marche** : `animcont = (VBTimer & $1C) >> 2`, 0..7, à l'horloge, même à l'arrêt
  (`WALKANIM`, `Objects.asm:107-112`). `EnemyAi.java:354-364` compte 0..15 → images de tir
  et de chute dans le cycle de marche.
- 🔴 ✔ **Tir** sans pose 40 ni durée `cont1=10`, bit 0 du sous-type ignoré
  (`CEDgofire`, `Objects.asm:2483-2493` contre `EnemyAi.java:197-207`).
- 🔴 Explosions 4× trop rapides : `animstep` = Canimcounter/4 (`Objects.asm:120-127,
  274-279`) ; +1 par tick dans `Combat.java:464`.
- 🔴 ✔ Explosions recalées au sol par `Simulation.followFloors()` ; l'original exclut les
  types 4 et 5 (`Objects.asm:1481-1487`).
- 🔴 Pas de dégâts au contact (`MNnewdirP` : `PlayerHit(obj_power)`, `Objects.asm:624-630`).
- 🔴 Projectile explosif → l'ennemi doit exploser, pas tomber (`DestroyEnemy`, test
  `o_param9`, `Objects.asm:1040`).
- 🔴 Évitement des murs : statut forcé à 2, sens de rotation collant `obj_rotdir`,
  `NewDirTable`, bits 5-6 de `bmstatus` (`Objects.asm:646-652, 2404-2459`) — remplacé par
  un demi-tour approximatif.
- 🔴 Les ennemis se traversent et traversent les décors (`MNctrlobjcoll`,
  `Objects.asm:570-580`).
- 🔴 Arme améliorée : second tir gratuit même quand le premier échoue faute d'énergie
  (`Objects.asm:1877` contre `Combat.java:140-142`).
- 🔴 Délai d'attaque : arithmétique sur octet signé (`Objects.asm:482-488`), valeur
  initiale `Rnd(64)` (`TMapMain.asm:1335`) et non 1 → tous les ennemis tirent ensemble.
- 🔴 Ennemi en vue mais à plus de 20 blocs : doit bouger (bit 7 de `bmstatus`).
- 🔴 Flash d'explosion `GlobalLight = -5` (`Objects.asm:392-401`) : jamais appelé.
- ⚪ Raccourci de visée par produit scalaire ; poursuite par produit vectoriel (zigzag) ;
  statut touché `Rnd(2)` et bit 1 du sous-type ; `CEDfire` → `Rnd(2)` ; bit 2 « reste
  collé » ; coins CT0-7 ; hauteur, inclinaison, vitesse et annulation du tir ennemi ;
  armes 1-2 « instantanées » inventées ; vitesse du joueur ajoutée au tir ; hauteur de
  canon par défaut 0 ; bornes sol/plafond des projectiles ; animation et éclairage des
  tirs ; recentrage de l'explosion d'un tir ; son d'explosion ; anti-répétition des sons
  d'impact ; `ObjSinCos` 236 ; `GunObj` garde le dernier doublon ; `Rnd` LFSR.

## 4. Rendu (`3d.asm`, `DrawScreen.asm`, `Objects.asm`)

- 🔴 ✔ **Sprites en pixels 2×1 et 1×2** : `ptmul` = 4 −1 (hauteur double) +1 (largeur
  double) (`Objects.asm:1555-1563`) ; `SCALE_SHIFT = 4` fixe dans `ObjectRenderer.java:39`.
- 🔴 **Facteur 9/8** sur la position latérale des sprites (`Objects.asm:1589-1592`)
  absent → sprites qui glissent sur le sol.
- 🔴 ✔ **Ciel 4× trop lent** : `CSkyRotation = (cap>>1)<<8` (`movement.asm:661-664`) ;
  `heading()/8` dans `FlatRenderer.java:138`.
- 🔴 Textures unpegged plus hautes que la brosse : alignées en bas dans l'original
  (`DrawScreen.asm:95-104`), en haut ici (`WallRenderer.java:198`, `Math.max(0, …)`).
- 🔴 Projectiles tracés après tous les objets, image 0, pleine lumière
  (`Renderer.java:239`, `ObjectRenderer.java:171, 190`).
- 🟠 Rayons prolongés de 10 cases pour les objets, un sur quatre (`3d.asm:270-336`).
- ⚪ Pas de la texture basse = distance (`DrawScreen.asm:329`, anti-tremblement) ;
  dénominateur des sols `y − horizon + 1` ; jonction plafond/mur à `yh` inclus ; ciel
  non animé ; viseur en (155, 96) ; pixels 2×2 par défaut dans l'original.

## 5. Interface, terminal, son (`Text.asm`, `Terminal.asm`, `Presentation.asm`, `Audio.asm`)

- 🔴 ✔ **Chiffres du panneau gris au lieu de bleus** : `ScorePrint` ne réécrit que ses
  plans (`0x11` MINI, `0x1D` DIGITAL) ; `Hud.blit` recopie le glyphe. Correctif :
  `(fond & ~plans) | glyphe`.
- 🔴 ✔ **Sons en boucle impossibles** : `playInstance()` (`JmeSoundPlayer.java:117`)
  force la lecture sans boucle et `stop()` n'a pas de prise sur une instance → FLAM ne
  boucle pas, s'empile, `stopFlame` sans effet. Bit 7 « alone » de `snd_mask` ignoré.
- 🔴 QUIT GAME sans « REALLY QUIT ? (Y/N) », et quitte le programme au lieu de revenir
  à la présentation.
- 🔴 Après game over, l'original reprend au niveau atteint (code enregistré) ; ici niveau 1.
- 🔴 Espace du moniteur : 4 px (`_` 6 px) dans l'original, 6 px ici.
- 🟠 Messages en jeu (`Messages`, `SprDelayPrintMessage`) : ramassage, clé requise, nom
  d'arme, PAUSE ON/OFF.
- 🟠 Le terminal ne passe pas sur l'arme achetée (`TMapMain.asm:388-392`).
- 🟠 Écran de chargement de niveau : noms de monde et d'arène, RETRIES LEFT, ACCESS CODE,
  PRESS ANY KEY, musique MUSL.
- 🟠 Musiques MUST (titre, crédits, fin) et MUSL (chargement, game over) jamais jouées ;
  la musique du niveau 1 démarre dès les logos.
- 🟠 Menu d'avant-partie et saisie du code (BAD ACCESS CODE, RESET ACCESS CODE).
- 🟠 Son sans distance ni priorité (`AllocSoundFX` : volume `64 − d²>>15`, coupure vers
  1448 unités, 4 canaux, canal réutilisé par objet ou porte).
- 🟠 Sons absents : pas STP4, ramassage, clic `GlobalSound2` (terminal, menu, arme, carte),
  `GlobalSound3` aux fondus.
- ⚪ Touches par défaut et touches réservées ; volume de musique par défaut au maximum ;
  menus qui bouclent ; mise en page et libellés du menu ; code du menu = code d'entrée de
  niveau ; écrans fixes à durée ; point d'angle de la carte sur case non relevée.

## Ordre de correction suggéré

1. Effets : `StopEffect2` et clés — deux corrections locales qui changent la progression.
2. IA : cadence `obj_cont1`, cycle de marche `WALKANIM`, pose de tir, explosions.
3. Rendu : `ptmul`, 9/8, ciel, explosions au sol, unpegged.
4. Panneau (couleurs), sons en boucle, sol nocif, carte automatique, voile rouge.
5. Collisions joueur/objets et ennemi/objets, chute, LookHeight.
6. Écrans, messages, menus, son spatialisé.
