package dev.mtgcraft.engine;

import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Boots Card-Forge once per JVM: unpacks its game data, installs {@link McGuiBase} and loads the card database
 * on a background thread. Nothing here touches Minecraft classes.
 */
public final class ForgeEngine {
    public enum State { IDLE, LOADING, READY, FAILED }

    private static volatile State state = State.IDLE;
    private static volatile String status = "";
    private static volatile Throwable failure;
    private static volatile Thread uiThread;
    private static volatile Path dataDir;

    /**
     * Card-Forge's "GUI thread". Engine callbacks that expect the EDT run here, and the screen posts every
     * player action here, so neither the game thread nor Minecraft's render thread ever waits on the other.
     */
    static final ExecutorService UI = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MTGCraft UI");
        t.setDaemon(true);
        uiThread = t;
        return t;
    });

    private ForgeEngine() {}

    public static State state() { return state; }
    public static String status() { return status; }
    public static Throwable failure() { return failure; }
    /** The folder holding game data, the image cache and the player's decks. */
    public static Path dataDir() { return dataDir; }

    static boolean isUiThread() {
        return Thread.currentThread() == uiThread;
    }

    /** Runs a task on the engine's UI thread. */
    public static void runOnUi(Runnable task) {
        UI.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        });
    }

    /**
     * Starts loading if it hasn't started yet (or failed before).
     *
     * @param dataDir    where game data, preferences and the card image cache live
     * @param resZip     opens the packed res.zip shipped in the mod jar
     * @param playerName name shown for the human player
     */
    public static synchronized void start(Path dataDir0, Supplier<InputStream> resZip, String playerName) {
        if (state == State.LOADING || state == State.READY) {
            return;
        }
        // A dedicated server passes a relative path ("./mtgcraft"); make it absolute so the zip-entry safety
        // check below compares like with like.
        Path dataDir = dataDir0.toAbsolutePath().normalize();
        state = State.LOADING;
        failure = null;
        ForgeEngine.dataDir = dataDir;
        Thread t = new Thread(() -> {
            try {
                status = "Unpacking card data";
                unpack(dataDir, resZip);
                writeProfile(dataDir);
                Files.createDirectories(dataDir.resolve("decks"));
                status = "Loading cards";
                GuiBase.setInterface(new McGuiBase(dataDir));
                FModel.initialize(null, ForgeEngine::adjustPrefs);
                GamePlayerUtil.getGuiPlayer().setName(playerName);
                status = "Ready";
                state = State.READY;
            } catch (Throwable e) {
                e.printStackTrace();
                failure = e;
                status = "Failed: " + e;
                state = State.FAILED;
            }
        }, "MTGCraft loader");
        t.setDaemon(true);
        // Background work: never let it crowd out the game's own threads (render, network, server tick).
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static Void adjustPrefs(ForgePreferences prefs) {
        // The card-based deck generator needs the 11 MB deckgendecks data we don't ship.
        prefs.setPref(FPref.DECKGEN_CARDBASED, false);
        prefs.setPref(FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER, true);
        prefs.setPref(FPref.UI_DISABLE_CARD_IMAGES, false);
        return null;
    }

    /** Unpacks res.zip into dataDir/res unless the same archive was unpacked before. */
    private static void unpack(Path dataDir, Supplier<InputStream> resZip) throws IOException {
        Files.createDirectories(dataDir);
        Path tmp = dataDir.resolve("res.zip.tmp");
        try (InputStream in = resZip.get()) {
            if (in == null) {
                throw new IOException("res.zip is missing from the mod jar");
            }
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        String stamp = Files.size(tmp) + ":" + crc(tmp);
        Path res = dataDir.resolve("res");
        Path stampFile = res.resolve(".stamp");
        if (Files.exists(stampFile) && Files.readString(stampFile).equals(stamp)) {
            Files.delete(tmp);
            return;
        }
        if (Files.exists(res)) {
            try (Stream<Path> walk = Files.walk(res)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(tmp))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                Path out = dataDir.resolve(e.getName()).normalize();
                if (!out.startsWith(dataDir)) {
                    throw new IOException("Bad zip entry " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out)) {
                        zin.transferTo(os);
                    }
                }
            }
        }
        Files.writeString(stampFile, stamp);
        Files.delete(tmp);
    }

    private static String crc(Path file) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                crc.update(buf, 0, n);
            }
        }
        return Long.toHexString(crc.getValue());
    }

    /** Points Card-Forge's user and cache folders inside dataDir instead of %APPDATA%/Forge. */
    private static void writeProfile(Path dataDir) throws IOException {
        String base = dataDir.toAbsolutePath().toString().replace('\\', '/');
        // Card-Forge reads this with Properties.load(InputStream), which decodes ISO-8859-1. Properties.store writes
        // that encoding and escapes anything else as \\uXXXX, so a game folder like C:\\Users\\José\\... survives.
        java.util.Properties p = new java.util.Properties();
        p.setProperty("userDir", base + "/user/");
        p.setProperty("cacheDir", base + "/cache/");
        try (OutputStream o = Files.newOutputStream(dataDir.resolve("forge.profile.properties"))) {
            p.store(o, null);
        }
    }
}
