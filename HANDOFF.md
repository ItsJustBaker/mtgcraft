# MTGCraft: handoff for the next Claude session

**Start here.** Read this file, then `PROGRESS.md` (the full history), then the code.

The user (itsjustbaker99) wants Magic: The Gathering inside Minecraft, using the Card-Forge rules engine
(github.com/Card-Forge/forge, GPL-3.0), as a **Forge 1.20.1 (47.4.0)** mod for their **ATM9** pack.
- They play it, and so do friends on a server.
- They're not a programmer. Keep replies short and plain.
- They care a lot about a **smooth, uncluttered UI** ("I dislike clunky / cluttered").

## Environment
- **Project:** `C:\Users\juanr\OneDrive\Desktop\kh\mtgcraft` (not a git repo).
- **Card-Forge checkout:** `C:\Users\juanr\dev\card-forge`. The four engine jars were built with
  `mvn -pl forge-gui -am install -DskipTests -Dcheckstyle.skip`, using Maven at `C:\Users\juanr\dev\tools\apache-maven-3.9.9`.
- **JDK 17:** `C:\Users\juanr\curseforge\minecraft\Install\runtime\java-runtime-gamma\windows-x64\java-runtime-gamma`.
  The default Java is 22; always export `JAVA_HOME` to the JDK 17 first.
- **Commands:**
  - Compile: `./gradlew compileJava`.
  - Dev client: `./gradlew runClient`, or the user double-clicks `run-client.bat`.
- **Logs:** I redirect output to `C:\Users\juanr\dev\runclient.log`. Crash reports go to `run/crash-reports`.
- **Headless engine tests (no Minecraft):** `C:\Users\juanr\dev\proto\bot\*.java` (MultiTest, PackTest,
  DeckTest, GauntletRepro).
  - Compile with `javac -cp engine/build/libs/mtgcraft-engine.jar` plus every `src/main/java/dev/mtgcraft/engine/**`
    source, then run with `-Xmx4G` and the res.zip at `build/mtgres/mtgcraft_data/res.zip`.
  - These are the fastest way to check engine changes.
- **Desktop control:** the user declined computer-use control. Verify through logs, headless tests and GameTests,
  then ask the user to try things in game.
- **Tools:** Python 3.13 with PIL and numpy, and `imageio-ffmpeg` (a bundled ffmpeg) for audio.

## Architecture (where things are)
- **`engine/`** (no Minecraft imports; Card-Forge wrapped):
  - `ForgeEngine`: background loader. It unpacks res.zip to `<gameDir>/mtgcraft`. Card-Forge's "EDT" is its own
    "MTGCraft UI" thread, never the render thread.
  - `McGuiBase`; `DuelGui` (extends `NetworkGuiGame`; its blocking dialogs become `ChoiceRequest`).
  - `Matches` (`MatchSetup` → HostedMatch; handles modes and variants); `DeckChoice`; `Decks`.
  - `Packs`: set boosters, 13 Minecraft themes, themed mob decks. Commander mob decks are 100 cards via
    `DeckgenUtil.generateRandomCommanderDeck` around a themed legend.
  - `Cards`: card key `Name|SET|cn`.
- **`engine/net/`:** Card-Forge's own network protocol over Netty in-memory local channels, bridged into Minecraft
  packets.
  - `RemoteSeat` is the server side; `RemoteDuel` is the client side.
  - Every human uses this path, including the single-player host.
- **`server/`:**
  - `DuelHosting`: launches a match plus tunnels and reports results.
  - `TableSessions`: Magic Table lobbies.
  - `GauntletDuels`: mob duels. Same-type mobs team up and nearby friends with gauntlets join. Commander when
    every human's deck is Commander-ready, otherwise classic. Bosses play Archenemy. Freeze, invulnerability,
    stakes, and a 60 s watchdog.
  - `DuelLoot`, `StarterKits`, `CollectionOps` (all card moves are server-validated), `MobThemes`.
- **`client/`:**
  - `DuelScreen`: the big 2D board, with an Arena view toggle.
  - `ArenaRenderer`: the 3D battlefield.
  - `TableScreen`, `DeckPickerScreen` (tabs: Colours, Precons, My decks, Commander), `DeckBuilderScreen`,
    `BinderScreen`, `PackOpeningScreen`, `StarterScreen`.
  - `DuelIntro`: the sting, camera stutter and letterbox.
  - `PersonalSounds`: the user's own `duel_start.ogg` override. Their Yu-Gi-Oh clip lives only in `run/mtgcraft`.
    **Never ship it in the jar.**
  - `CardTextures` / `CardArt` / `CardItemRenderer`: Scryfall art.
- **`item/`:** CardItem, PackItem, BoxItem, BinderItem, DeckBoxItem (commander slot), UniversalDeckBoxItem
  (holds a DeckChoice, no physical cards), GauntletItem, and CardBag (NBT card lists).
- **`net/`:**
  - `Net` registers every packet. `toPlayer` skips fake players, because those crashed servers.
  - `Packets` defines them all.
- **Art and model generators (Python):** `tools/make_pack_art.py`, `tools/make_3d_models.py`,
  `tools/make_duel_sting.py`, `tools/make_test_arena.py`.
- **Build (`build.gradle`):**
  - `engine/` shades Card-Forge plus about 70 libraries, relocated under `dev.mtgcraft.shadow.*`. Import relocated
    types from there, e.g. `dev.mtgcraft.shadow.io.netty...`.
  - The `resZip` task packs the needed `res/` folders (including commander precons and draft rankings).

## State at handoff (2026-10-04)
Compiles. The user has played and confirmed: tables, multiplayer, packs, binder, deck box, gauntlet duels, intro
sound, card art.

**Written and compiling, but never seen in game:**
- Universal Deck Box (item, recipe, deck picker, packet).
- Commander 100-card rule, with automatic classic fallback.
- Deck Builder redesign: commander slot, ♛ crown button on legends, hover hints.
- Commander tab in the deck picker.
- 3D pack and box models.
- Arena renderer v1: billboarded floating cards.

## Session 4 (2026-10-04, cloud): the user's "cluttered UI" list
Done and compiling; the duel screen was checked with screenshots from a real dev client (see "Dev duel harness"):
- **Wrong win/loss fixed:** Card-Forge's `getWinningTeam()` is -1 for a plain last-player-standing win, so the
  gauntlet treated wins as losses (killed the player, mobs lived). `Matches` now reads the winner's team.
- **Mobs always die when they lose; jockeys** (a mob riding a mob) are one seat named "<Mount> Jockey".
- **Scry/surveil** "any number" choices could never be confirmed (negative bounds in `DuelGui.order`); fixed.
  `manipulateCardList` (top/bottom moves) now asks; identical simultaneous triggers aren't asked to be ordered.
- **Damage split screen:** new `ChoiceRequest.Kind.DISTRIBUTE` for combat damage (trample rule checked) and
  divided damage/counters.
- **DuelScreen:** log in a toggle panel (L); upright status chips (P/T green/red vs printed, counters, "Zz" for
  summoning sickness, "→ target" for attackers in multiplayer); step banners for attack/block; plain sidebar
  hints; Log / Zones / 2D-3D tool buttons; zone viewer (Z, zone chips, right-click a player) for every player's
  graveyard, exile, command zone, library and hand, cards clickable; a pick tray in the middle for targets that
  aren't on the table; commanders beside the hand (castable, tax shown); commander damage chips; dragging a
  creature at an opponent in the main phase goes to combat and attacks; "Who goes first?" picker; overlays drawn
  above card badges; gauntlet games end with "Collect reward".
- 3D item models for the Deck Box, Universal Deck Box and Duel Gauntlet (`tools/make_gear_models.py`).

### Dev duel harness
`./gradlew runServer -PdevDuel` then `./gradlew runClient -PdevDuel` (client auto-joins localhost): the server
challenges the player with a spider jockey and a zombie; the client screenshots the duel screen every 2.5 s into
`run/screenshots/` and plays along (lands, a spell, attacks, default answers). Needs `run/eula.txt` and
`online-mode=false` in `run/server.properties`. Code: `dev/DevDuel*.java`, off without the flag.

## Session 6 (2026-10-05, cloud): playtest feedback round 2 (v0.2.0)
- Auto-pass (`engine/net/AutoPass`, hooked in `RemoteSeat`): the server passes priority for a human with no
  playable land/spell/ability they can pay for. Decisions (blocks, targets, questions) are never skipped.
- Sounds: your turn, end of your turn, blockers to declare, a question.
- Block checks: flying/reach, shadow, horsemanship, tapped; a block the game rejects (it flashes) is removed from
  the planned blocks with a message; menace warns. Toasts via `DuelScreen.toast`.
- Keyword chips, hover box (keywords, counters, attachments), lines from auras/equipment to their host.
- Player skin / mob head portraits (`client/Heads`), pile hover highlights and captions, concede flag bottom-left.
- Popups (`Packets.Prompt` → `client/PromptScreen`) for duel invites and join offers; team names; titles.
- Mob tiers (`MobThemes.Tier`): critters 6 life, common 12 (quick classic duels, config quickDuels), tough = full
  game, bosses Archenemy / Archenemy Commander (new `MatchSetup.Mode.ARCHENEMY_COMMANDER`, 60 life). Modded bosses
  by forge:bosses tag, name list or max health >= 150.
- Rewards per duelist (`DuelLoot.duelRewards`) into the inventory + `client/RewardsScreen`; defeated mobs drop no
  extra packs. Safe time after duels (config graceSeconds) and mobs can't target duelists.
- Ranged challenge: right-click the air aiming at a mob (32 blocks); PartEntity (dragon/hydra parts) → parent.
  The Ender Dragon is held in HOVERING phase on its spot (it ignores NoAI).
- Underground: `fitRadius` shrinks the arena to open space; duelists only move to clear spots.
- Arena: stack floats in the centre, new permanents fly in from the centre, exile is a void vortex.
- Binder shift+right-click files loose cards; "View card" key (Z) shows the held/hovered card big.
- Creative-only Win button (`Packets.CheatWin`, opponents to 0 life). Gauntlet kept on death like the Deck Box.
- Deck box models closed (no see-through gaps).

## Session 7 (2026-10-05, cloud): packs, booster picks, phase stops (v0.2.4)
- 40 new pack themes appended to `Packs.Theme` (never reorder: saved packs and item-model predicates use the
  order). 20 vanilla (Celestial for allays/vexes/phantoms, Hive, Golem, Piglin, ... Archer) and 20 All the Mods
  add-on themes with `mods` ids; `Theme.available()` hides them unless one of the mods is loaded.
  `MobThemes.theme` maps vanilla mobs by exact id, ATM mods by namespace, then falls back to name words.
  Art: `tools/make_pack_art.py` (ICONS/WRAP) + `tools/make_3d_models.py` (THEMES list = enum order). After running
  them, `git checkout` the changed existing textures (the user edits art by hand).
- Real set boosters: 10% per beaten mob in duels (config duelSetPackChance), wandering traders and librarians
  sell them (BoosterTrades, config boosterTrades). Booster Pick tickets (`PackItem.ticket`, tag Pick=PACK/BOX)
  from bosses (35% / 4%): right-click opens `client/SetPickerScreen`, `Packets.PickSet` redeems.
- Phase stops: click the phase bar in a duel to skip phases; client config skipPhases (bitmask) goes with
  `Packets.AutoPassPref` and `AutoPass` feeds Card-Forge's own `YieldController.setSkipPhase`. Net VERSION 4.
- Harness: `-PdevPools` (pool sizes per theme), `-PdevPicker`, `-PdevSkip`.

## Next tasks, in the order the user asked
0. **ATM10 version** (Minecraft 1.21.1 / NeoForge): the user asked for a separate build.
1. **Yu-Gi-Oh style 3D field** (`client/ArenaRenderer`); the user's latest request. They want:
   - **Cards lying flat on the floor** in glowing card zones in front of each duelist, like a Yu-Gi-Oh duel field:
     a creature row and a land/other row, each a row of zone frames.
   - **Players and mobs on podiums:** a glowing pedestal or hex platform with light pillars under each seat
     entity. Rendering only; don't teleport players.
   - **A cool background:** an energy-barrier cylinder wall around the arena, a glowing grid or runes on the
     floor, and maybe a darker sky inside the arena.
   - Keep tapped (rotate 90° in the floor plane) and attacking (slide toward the centre) states.
   - Use `RenderType.lightning()` for coloured quads and `entityTranslucentEmissive` for card art.
   - Bosses get a bigger arena.
2. **Commander UI in `DuelScreen`:** each player's command zone (`pv.getCards(ZoneType.Command)`) shown next to
   their name tag or pill, clickable to cast; commander damage (`pv.getCommanderDamage(commanderCard)`; 21 is
   lethal); a 40-life display. Keep it uncluttered.
3. **Fix the GameTests** (`test/MtgGameTests`, run with `./gradlew runGameTestServer`).
   - They still run out of memory. The cause is setup re-running every tick inside `succeedWhen`;
     `FakePlayerFactory.get` with a new GameProfile leaks a player each time.
   - Fix: one shared fake player per test, and put all setup before `succeedWhen`.
   - Also note: the GameTest server ticks unthrottled.
4. Then the backlog:
   - Sealed at the table (pools from `Packs.openSet`, then `DeckBuilderScreen`-like building).
   - Card Merchant villager.
   - Packs in loot chests (a Global Loot Modifier).
   - ATM9 premium packs (Allthemodium / Vibranium / Unobtainium, behind `forge:mod_loaded` conditions).
   - Draft.
   - Lazy card loading for memory.
5. **Release:**
   - Run `./gradlew build`; the jar lands in `build/libs/`. Put it in the ATM9 instance
     (`C:\Users\juanr\curseforge\minecraft\Instances\All the Mods 9 - ATM9 (1)\mods`) and on their server.
   - Servers need about 4 GB of heap or more for the card engine.

## Gotchas learned the hard way
- **Image keys:** CardView image keys (`c:Name|SET|art`) must be converted to cache file keys before
  `ImageKeys.getImageFile`; `CardTextures.fileKey` does this. Also call `ImageKeys.clearMissingCards()` after a
  download.
- **Threads:** game state is mutated on the game thread. Wrap rendering in try/catch; `DuelScreen.render` already
  skips bad frames.
- **Network sync:** values like `PlayerView.getMana()` can be null until the first network sync.
- **Concede during setup:** Card-Forge can't take a concession while it's still dealing. `DuelHosting.Handle.concede`
  waits 6 s and then releases pending replies (`RemoteSeat.cancelPending`).
- **Dedicated servers:** they pass relative paths; `ForgeEngine.start` normalizes them.
- **Player order:** HostedMatch puts humans first, so seat order ≠ in-game player order. Map seats by player
  name, as `ArenaRenderer` does.
- **Copyright:** never bundle copyrighted audio or art. Generated art and Scryfall downloads at runtime (like
  Card-Forge itself does) are fine.

## Session 5 (2026-10-04, local): merged the cloud session into kh/mtgcraft, then
- Personal duel clip restored (only in run/mtgcraft and the ATM9 instance's mtgcraft folder, never in the jar).
- Starter kits are 100-card Commander decks (random legend of the chosen colour pair).
- Mana counter on player tags: orb + "untapped/total" mana sources (+floating); open mana on opponent tags.
- Blocking feedback: planned blocks show at once (gold lines, "→ attacker" chips, "Your blocks: ..." banner).
- Boosters tab lists every printable set (170, Alpha → newest incl. Spider-Man, Avatar, Aetherdrift, FF), oldest first.
- 3D binder model (tools/make_gear_models.py).
- Epic arena (client/ArenaRenderer): floating stage above the terrain (server/GauntletDuels.stageHeight), forced
  seating around the ring, players fly + are locked to their podium, mobs no-gravity; dark rune floor texture,
  barrier wall, dark fog, podiums sized to the mob, flat card zones, creature holograms, deck/graveyard/commander.
- 1v1 duels by default; shift+right-click the gauntlet in the air for group fights (config groupFights).
- Settings screen from the Magic Table (server settings for host/ops, plus client: duel intro, arena view).
- Dev harness: `./gradlew runClient -PdevDuel -PdevWorld=DevArena -PdevArena` runs in single-player on a copied
  save and writes duel_*.png / arena_*.png to run/screenshots.
- Release jar built and copied to the ATM9 instance mods folder.

## Session 5b: invites and group battles
- Right-click a player with the Duel Gauntlet: if that player is starting a mob duel, you join it. Otherwise they get a friendly-duel challenge with clickable [Accept]/[Decline] that lasts 60 s. In a friendly duel nobody dies and the winner is announced.
- Mob challenges: friends within 8 blocks who hold a gauntlet and have a ready deck get a clickable [Join] and 10 s to use it. The duel starts early once all of them have joined. If no friends are nearby, it starts right away.
- Only friends still within 16 blocks of the challenger at the start get pulled onto the stage and locked in.
- The gauntlet's group/1v1 toggle now only controls whether extra mobs join.
- Command: `/mtgduel accept|decline|join <player>` (`GauntletDuels.commands`). Code: `GauntletDuels` Lobby, INVITES, startPvp.

## Session 8 (2026-10-06, local on the friend's PC, Zazael1): 0.2.6 test + fixes
Tester is the user's friend (Zazael1, Windows user `cruzd`, Pro plan: keep token use low, they cap a session at ~35%).
**Nothing below has been checked in game yet**: ask them to try it in the `MTG` CurseForge instance (0.2.6 jar installed).

**Building without Card-Forge** (this PC has no card-forge checkout): extract the engine from a released mod jar.
From `mtgcraft-0.2.x.jar`, take `forge/**`, `dev/mtgcraft/shadow/**`, the loose root files and `META-INF` (minus
`mods.toml`/`MANIFEST.MF`) into `mtgcraft-engine.jar`, and `mtgcraft_data/res.zip` into a res folder. Then locally
(never commit): drop `include 'engine'` from settings.gradle, point `engineJar` at that file and the resources srcDir
at the res folder. Needs JDK 17 (Temurin zip); the PC only has Java 8 and 25.

Done this session:
- 0.2.6 code reviewed (concede freeze, gauntlet-on-trader, mob deck size, boss scaling): looked right.
- Glove worn on the hand: `client/GloveRenderer` (render layer + RenderHandEvent; held third-person model scaled to 0).
  User said the first version sat "in/inside the hand"; resized to wrap the arm. **Still needs their confirmation.**
- "Delayed death" meant the *player* dying after a lost duel: `p.kill()` fallback when armour soaks the hit.
- Auto-pass stops: phase bar click cycles normal → always stop (green line) → always skip. Stops ride in bits 16+
  of the existing AutoPassPref skips int (`STOP_PHASES` client config); `AutoPass.stopsHere`.
- Playable glow: server sends `setWeaklySelectable` (playable cards) at each priority prompt; client glows green
  (`HIGHLIGHT_PLAYABLE`, Settings toggle).
- Turn signal: `DuelScreen.drawTurnSignal` (YOUR TURN splash + gold frame while waiting on you).
- Shift on a two-sided card's zoom shows its back; the top stack card is drawn big mid-board (`drawStackLabel`).
- Deck choice: user rejected shift-click; carrying 2+ decks pops a "Which deck?" Prompt (`/mtgduel deck <slot> <mob>`),
  the pick is marked active (glint) and preferred by `DeckBoxItem.find`. PromptScreen stacks >2 buttons.
- `client/CardFilter`: W U B R G C / type / foil filters + type-line search in the deck builder and binder.
- Join error "Index 0 out of bounds" was NOT MTGCraft: client drops while decoding the recipe sync; Quark configs
  differ between host and friend (Shiba data-ID mismatch). Fix = copy the host's `config` folder to the friend.
- GameTests: added `gauntletOnVillager`, `mobDeckMatchesLibrary` (both pass; the suite still OOMs late, as before).

Still to do from the 0.2.6 list: card frame item (6 per block), Binder hold position + 3D flip-through Binder,
commander/first-card art on deck boxes. Multiplayer (concede together, join) still untested with two real players.

## Session 9 (2026-10-07, local on Zazael1's PC): 0.3.0 part 1
**Permanent build tools now at `C:\Users\cruzd\mtgcraft-build`** (JDK 17, `mtgcraft-engine.jar`, `mtgres/`). Apply the
local build.gradle/settings.gradle edits from Session 8 pointing there; never commit them.
User's 0.3.0 list + decisions: `Downloads\updates 0.2.6 - 0.3.0.txt`. Card conditions: CUT. Booster art: download real
pack art at runtime (not bundled). Arena is the feature they want most. **Rule: no update may break saves**
(never rename registry ids, NBT tags or config keys). Weekly usage is tight (66%): batch work.

Done (compiles; not yet tried in game):
- Glove slimmed (user: too big, didn't reach the hand's end): `GloveRenderer` scale 4.4/7, 7.5/15, 4.7/5 at y 7.75.
- Engine bridge (`DuelGui`): "Other..." numbers and capped numbers now use the number picker (X > 9 was fizzling);
  free-text "creature/card/land type" questions show a list of every type (was returning nothing → card fizzled).
- Fix button in the duel tool row → `/mtgduel unstick` (cancel stuck questions + resend full state; 2nd press within
  20 s ends as a draw). A duel now ends at once when every player in it died.
- `server/KeepOnDeath`: glove + active deck leave the inventory at LivingDeathEvent HIGHEST (before tombstones) and
  come back on respawn/login (stored in persisted NBT).
- Starter Kit item (`starter_kit`): given once on join instead of the forced menu; right-click opens the menu; choosing
  uses up a kit. Existing players who already chose get nothing.
- Arena v1: inside a duel arena the fog closes in over 1.5 s (world "pulled away") into deep-space colour, with 260
  twinkling stars on a dome (`ArenaRenderer.stars`). Shader packs may override the fog; untested with Complementary.
- Version 0.3.0. GameTests still OOM before reporting (pre-existing).

Next (user's order): card names needed for bugs 46 (land not entering tapped) and 48 (5/2 vs 2/1); Jumpstart starter
decks; player duel modes/stakes; difficulty; hand-card zoom; deck UI revamp; packs/loot; Champion villager + World Cup
boss; quests; runtime booster art; arena v2 (floor/visuals, shaders); AI orb teammate; holo parties; Duel Disk glove;
join "Index 0 out of bounds" still unexplained (ask for the error with -Dforge.logging.console.level=debug).

### Session 9, part 2 (same day): more of 0.3.0
All compile; none tried in game yet. Pushed with a 0.3.0 jar on the `downloads` branch.
- Difficulty (`difficulty` -2..2, Settings menu): mob AI life x(1+0.25d), mob deck packs +d. `chestPackChance`:
  `server/ChestLoot` adds a themed-pack pool to every `chests/*` loot table.
- Hand cards: hovered hand card scales 1.9x (sharp texture).
- Player duels: right-click a player → menu (Friendly Commander/Classic, To the death, Ante 3 cards, Reward packs
  (needs World Cup, once per MC day per pair), Invite/Leave party). Stakes paid in `GauntletDuels.applyStakes`.
- `server/Champions`: one Village Champion per 64 blocks (tag `mtgcraft_champion`, name "Village Champion"); losing to
  it is safe, beating it gives a pack + best card of a themed pack (daily) and the World Cup once. Rendered as a player
  with `textures/entity/champion.png` (the user's skin) by `client/ChampionRenderer`. `champion_spawn_egg` item.
- Villager card-for-card trades (`BoosterTrades.cardTrades`: librarian, cleric, cartographer).
- 10 new pack themes appended to `Packs.Theme` (RODENT SPIDER PHANTOM LUSH TRAIL ENDERMEN NECRO SAFARI QUARK FORBIDDEN);
  they use the default pack model (no per-theme art yet).
- Starters: colour choice prefers a real Commander precon of exactly those colours; "Surprise me" = random precon.
- Duel Familiar (`familiar_orb`, craftable): learns a deck box's deck; joins mob duels as an AI teammate (team 1).
  The user also wants to be able to *play* the familiar themselves (needs one client driving two seats): not done.
- Parties + holo battles (`server/Parties`, menus only): party members anywhere get a "Holo battle!" prompt when a
  member's lobby opens; home saved in persisted NBT, sent home in `end()` before penalties.
- Duel Disk (`duel_disk`, crafted from the gauntlet): alternative look, forearm-mounted (`GloveRenderer`).
- Quests = advancement tab `mtgcraft:quests/*` with pack/XP rewards; duel quests granted by `server/Quests`.

Still not done: group PvP (pull nearby players into one free-for-all), deck screen revamp (ask user what bugs them),
real booster art at runtime (no public pack-image source; plan: Scryfall card art on set packs via a BEWLR), arena v2
(floor, shader check), teleport-mid-duel bug (10; Fix button is the workaround), card names for bugs 46/48, more ATM9
themes (5 of 20), playing your familiar yourself, per-theme art for the 10 new themes.
