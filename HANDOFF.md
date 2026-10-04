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

## Next tasks, in the order the user asked
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
