# MTGCraft: progress notes (stopped 2026-10-04)

Goal: play Magic: The Gathering against Card-Forge's AI inside Minecraft.
- Target: **Forge 1.20.1 (47.4.0), the ATM9 pack**, Java 17.
- Phase 1: a Magic table block. Right-click it to open a full-screen duel.
- Phase 2 (later): a 3D tabletop in the world.
- UI requirement from the user: **smooth, not clunky**. Drag cards from hand to the battlefield to play
  them, drag attackers at the opponent, drag a blocker onto an attacker, cards animate between zones,
  hover to zoom, Space = OK, buttons kept out of the way.
- License: Card-Forge is GPL-3.0, so this mod is GPL-3.0-or-later.

## Done
- Card-Forge cloned to `C:\Users\juanr\dev\card-forge` (outside OneDrive; it's 787 MB).
  - Engine built with Maven (`C:\Users\juanr\dev\tools\apache-maven-3.9.9`) using the JDK 17 from CurseForge:
    `C:\Users\juanr\curseforge\minecraft\Install\runtime\java-runtime-gamma\windows-x64\java-runtime-gamma`
  - Build command, run in the card-forge folder with JAVA_HOME set to that JDK:
    `mvn -pl forge-gui -am install -DskipTests -Dcheckstyle.skip`
- **Headless proof** (`C:\Users\juanr\dev\proto`): `Proto.java` + `HeadlessGui.java` boot the engine and
  play a full AI-vs-AI game. Startup takes 3.5 s; a game takes about 3–7 s.
- **Trimmed data works.** These parts of `forge-gui/res` are all the engine needs (about 37 MB):
  - card scripts, zipped as `cardsfolder/cardsfolder.zip`;
  - `tokenscripts`, `editions`, `blockdata`, `setlookup`, `formats`, `lists`, `ai`, `defaults`, `effects`;
  - `quest/precons` and `languages/en-US.properties`.
  - Set the pref `DECKGEN_CARDBASED=false` to silence the missing-deckgendecks errors.
- Mod project created from the Forge MDK (`mtgcraft/`). Mod properties are set in `gradle.properties`.
  - `engine/build.gradle` shades Card-Forge plus about 70 libraries into one jar. Guava, Netty, Gson,
    slf4j and the rest are relocated under `dev.mtgcraft.shadow.*` so they can't clash with Minecraft's
    versions.
  - Root `build.gradle` puts that jar on `minecraftLibrary` for dev runs and merges it into the release jar.
  - The `resZip` task packs the trimmed card data into `mtgcraft_data/res.zip` inside the jar.
  - **Not built or run yet.**

## Design (decided, not yet written)
Engine package `dev.mtgcraft.engine` has no Minecraft imports, so it can be tested headlessly.
- `ForgeEngine`: background loader.
  1. Unpacks res.zip to `<gameDir>/mtgcraft/` (skips this when the stamp is unchanged).
  2. Writes `forge.profile.properties` (userDir and cacheDir, forward slashes).
  3. Calls `GuiBase.setInterface` and `FModel.initialize`.
- **Threading.** Card-Forge's "EDT" is a dedicated single thread, "MTGCraft UI", **not** Minecraft's
  render thread.
  - `isGuiThread()` returns true only on that thread.
  - Every screen action (`selectCard`, `selectButtonOk`...) is posted to that thread.
  - Blocking dialogs go into a `ChoiceRequest` queue and wait on a `CompletableFuture` that the screen
    completes. Minecraft never freezes.
- `McGuiBase implements IGuiBase` (pattern: `forge-gui/tools/java/ForgeMatrixWriter.HeadlessGui`).
- `DuelGui extends AbstractGuiGame`. Its 27 abstract methods are listed in the session.
  - Prompt and buttons: stored state that the screen polls.
  - getChoices, confirm, order, abilities, entities: answered through `ChoiceRequest`.
  - Combat damage: auto-assigns lethal damage in order.
  - Sideboard: returns the main deck unchanged.
  - `isUiSetToSkipPhase`: stops at your Main 1 and Main 2, the opponent's declare-attackers step, and
    the opponent's end step.
- `McImageFetcher extends ImageFetcher`. Copy `LibGDXImageFetcher`'s download logic using java.nio.
  Load images in Minecraft by decoding with ImageIO into a `NativeImage`, because `NativeImage.read`
  only accepts PNG.
- Start a match: `HostedMatch.startMatch(GameType.Constructed, null, players, human, gui)` on the UI thread.
  - Human player: `GamePlayerUtil.getGuiPlayer()`.
  - AI player: `GamePlayerUtil.createAiPlayer(name, idx)`.
  - Decks: `DeckgenUtil.buildColorDeck(List.of("White","Blue"), null, forAi)` or `getRandomColorDeck`.
- How screen gestures map to engine calls:
  - Click a card: `selectCard`. Right-click: an ITriggerEvent with button 3, which removes it from combat.
  - Drag from hand to the battlefield: `selectCard`.
  - Drag a creature at the opponent while declaring attackers: `selectCard`.
  - Drag a blocker onto an attacker: `selectCard(attacker)`, then `selectCard(blocker)`.
  - Drag a spell onto a target: cast it, then a pending target is clicked once it becomes selectable.

## Session 2 (done)
- Engine bridge written in `src/main/java/dev/mtgcraft/engine/`:
  - `ForgeEngine`, `McGuiBase`, `McImageFetcher`, `DuelGui`, `ChoiceRequest`, `Duels`.
  - Decks are generated from Modern-legal cards only.
- **Bridge tested headlessly.** `C:\Users\juanr\dev\proto\bot\BotTest.java` plays the human seat through
  `DuelGui`: it plays cards, pays mana, discards and passes. A full game reached turn 14 with nothing hanging.
- Minecraft side written:
  - `MtgCraft` and `MagicTableBlock`: the block, its model and textures, a recipe
    (book over 3 green wool over 2 sticks), and the Functional Blocks creative tab.
  - `ClientHooks`, `LobbyScreen`, `DuelScreen`, `CardTextures`, `Theme`.
- `gradlew compileJava` passes. `gradlew runClient` loads the mod with no errors.
- **Not yet verified in game:** the lobby, the duel screen and card-art downloads. The user declined screen
  control, so the duel has never been seen running in Minecraft.
- Note: relocated library types must be imported under `dev.mtgcraft.shadow.*` (e.g. `org.jupnp`).

## Session 2, after user testing
- Card art works. The bug: CardView image keys (`c:Name|SET|art`) must be converted to cache file keys with
  `ImageUtil.getPaperCardFromImageKey(...).getCardImageKey()` before `ImageKeys.getImageFile`.
- Deck picker per side: Colours / Precons (505, searchable) / My decks (`run/mtgcraft/decks`, paste a decklist
  from the clipboard via `DeckRecognizer`, or open the folder). Code: `Decks`, `DeckChoice`.
- Large hover preview (up to 80% of the screen height, full-resolution texture). The phase strip auto-fits.
- Dedicated "MTGCraft" creative tab.
- `run-client.bat` launches the dev client with the correct JDK.

## Roadmap agreed with the user (2026-10-04)
1. **Phase 1 (code done, in-game test pending):** games run on the server; mode select; 2-8 seats, AI or player;
   teams. Friends join over a normal MC server.
2. **Phase 2:** crack packs. Sealed and Draft, a deck builder, and a pack-opening animation (tear the wrapper,
   flip cards one by one, rare last).
3. **Phase 3, survival adventure:**
   - Card items with real art (they work in item frames); a Binder (3x3 pages like TCG Card Shop Sim) and a
     Deck Box. Trading by dropping items.
   - Duel Gauntlet item: right-click a mob or player to challenge them. Same-type mobs nearby team up; each
     team fights everyone.
   - A "View: Screen / Arena" option. The Arena is an in-world 3D battlefield with a rune circle and
     holographic cards; it's compact for normal fights and big for bosses.
   - Stakes: win means the mob dies and drops cards or packs; lose means you die, but you keep your deck box.
     Configurable.
   - Duel safety: everyone in a duel is frozen and invulnerable.
4. **Phase 4:**
   - Packs with Minecraft-art wrappers (real MTG cards inside, themed by mob or biome).
   - Bosses drop themed booster boxes (Ender Dragon gives black dragons); bosses fight as the Archenemy.
   - ATM9 tie-ins: Allthemodium, Vibranium and Unobtainium premium packs; ATM9 bosses. Soft dependency.
   - Card Merchant villager; packs in loot chests; spectating; life totals floating over tables;
     a two-sided trade window; advancements.
5. Before release: lazy card loading for memory; watchable AI turns (pause and highlight each play).

## Phase 1 implementation
- The engine runs on the logical server (`ServerEvents` loads it on `ServerStartedEvent`). Clients also load
  it, for card art and decoding.
- Transport: `engine/net/Tunnel` runs Card-Forge's own network codec and handler on Netty in-memory local
  channels. A bridge channel copies the bytes into MC packets (`net/Packets.Tunnel`, 30 KB chunks).
  - Server side: `RemoteSeat` (a `RemoteClientGuiGame` per human seat).
  - Client side: `RemoteDuel` (a `DuelGui` plus a `NetGameController`).
  - Everyone uses the tunnel, including the single-player host.
  - Headless test: `C:\Users\juanr\dev\proto\bot\MultiTest.java` (4 players, one over the tunnel). It passes
    in free-for-all and Commander.
- Lobby: `server/TableSessions` holds one lobby per table, and the host is whoever opened it first.
  `client/TableScreen` and `client/DeckPickerScreen`. A player's own decks are sent as .dck text (`DeckChoice.INLINE`).
- Modes (`MatchSetup.Mode`): Free-for-all, Teams, Commander, Brawl, Oathbreaker, Planechase, Archenemy,
  Momir Basic, MoJhoSto.
- `DuelScreen` gives each opponent a column with a name tag. Dropping an attacker on a column attacks that player.

## Session 3 (stopped 2026-10-04 ~15:00; the build compiles and is playable)
Done this session:
- Phase 1 tested by the user. Max seats is 4. The multiplayer board has focus-weighted opponent columns.
- Crash-proof duel screen: a bad frame is skipped and logged instead of crashing the game.
- Packs (`engine/Packs`):
  - 47 real set boosters (36-pack boxes) and 13 Minecraft themes.
  - Boss boxes: Ender Dragon and Wither.
  - Themed mob decks use `SealedDeckBuilder`, with a commander from the theme in Commander duels.
- Items:
  - Card (renders real art via `CardItemRenderer` and works in item frames).
  - Booster Pack and Booster Box: 3D models made by `tools/make_3d_models.py`; the wrapper art comes from
    `tools/make_pack_art.py`.
  - Binder, Deck Box (has a commander slot: right-click a deck row) and Duel Gauntlet.
- Screens: pack opening, binder, deck builder, starter deck choice.
- Creative tabs: MTGCraft, and MTGCraft Boosters (all sets).
- Duel Gauntlet (`server/GauntletDuels`):
  - Casual Commander by default (config `duelMode`); bosses play Archenemy.
  - Same-type mobs team up, and nearby friends holding a gauntlet join. Everyone is frozen and invulnerable.
  - Penalty config: DEATH, DAMAGE or NONE. The Deck Box is kept on death.
  - A watchdog ends a stuck duel as a draw after 60 s.
- Duel intro: original sting, camera stutter, letterbox. A personal `mtgcraft/duel_start.ogg` overrides the sound
  via `PersonalSounds`. The user's Yu-Gi-Oh clip is in run/ only; never ship it.
- 3D battlefield: `client/ArenaRenderer` draws holographic cards and a ring, for gauntlet duels and tables.
  DuelScreen has View: Arena/Screen (V).
- Mob drops, boss boxes and the Ender Dragon box (`server/DuelLoot`); starter kit (`server/StarterKits`);
  config in `MtgConfig`.
- Real bugs fixed:
  - Relative data dir on dedicated servers.
  - Packets sent to fake players crashed the server.
  - Concede during setup hung the game.
  - Pending replies are now released on concede.

## 0.2.6 (bugs and balance from the user's 0.2.6 list; written without the engine jars, so not compiled here)
- Concede freeze: an in-screen concede now releases the game's pending question (`RemoteSeat`), the duel screen drops
  questions left open after game over (`DuelGui.currentRequest`), and the server counts in-screen concessions so its
  watchdog frees everyone 15 s after all players conceded (`GauntletDuels.Duel.conceded`).
- Gauntlet on villagers/traders: an `EntityInteract` handler (`ServerEvents`) starts the duel before the mob's own
  right-click runs.
- Late mob deaths (ATM9 damage caps): the win clears hit cooldown, kills again, and falls back to `die()`.
- Joining a friend: the client loads cards 20 s after it's in the world (not at login), on a low-priority thread.
  The logs showed a clean "Disconnected" twice, no crash; ask the friend what they saw if it still happens.
- Creature types: 40 instead of 24, in a 5-column grid.
- Balance: in classic duels mob decks grow to the player's library size (`Packs.growDeck`); bosses get
  `bossLife` (30) + `bossLifePerExtraPlayer` (15) per extra player, and solo bosses build from `bossSoloPacks` (8).
- Left for later from the 0.2.6 list: filters, foils, deck picker, varied starters, auto-pass stops, playable
  highlights, turn banner, card backs, card frame, glove/binder visuals, 3D binder, deck pictures, played card in the
  middle.

## Known issues
- `gradlew runGameTestServer` still runs out of memory. Some test (probably `starterKit` or `openThemedPack`) loops
  creating FakePlayers: `FakePlayerFactory.get` with a new profile every call leaks. Fix: make one shared test
  player, and set up outside `succeedWhen`.
- None of the session-3 features (arena, 3D items, Commander gauntlet) has been seen in game by me yet.

## Next steps
1. Playtest session-3 features; fix what breaks.
2. Sealed at the table; Card Merchant villager; packs in loot chests (GLM); ATM9 premium packs; Draft.
3. Build the release jar (`gradlew build`; output in build/libs) and install it into ATM9. The ATM9 server needs the
   jar too. Lazy card loading for memory.
2. Build the jar and install it into the ATM9 instance
   (`C:\Users\juanr\curseforge\minecraft\Instances\All the Mods 9 - ATM9 (1)\mods`).
3. Check heap use. Loading all cards eagerly is heavy; consider `LOAD_CARD_SCRIPTS_LAZILY`.
