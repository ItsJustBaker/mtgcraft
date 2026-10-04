package dev.mtgcraft.engine;

import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
// Relocated by engine/build.gradle (shadow).
import dev.mtgcraft.shadow.org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Card-Forge's platform layer for Minecraft. The "EDT" is {@link ForgeEngine#UI}. App-level dialogs are forwarded
 * to the running duel when there is one; skins, sound and downloads are not used.
 */
final class McGuiBase implements IGuiBase {
    private final String assetsDir;
    private final ImageFetcher imageFetcher = new McImageFetcher();

    McGuiBase(Path dataDir) {
        this.assetsDir = dataDir.toAbsolutePath() + File.separator;
    }

    @Override public boolean isRunningOnDesktop() { return true; }
    @Override public boolean isLibgdxPort() { return false; }
    @Override public String getCurrentVersion() { return "MTGCraft"; }

    @Override public void invokeInEdtNow(Runnable runnable) { runnable.run(); }
    @Override public void invokeInEdtLater(Runnable runnable) { ForgeEngine.runOnUi(runnable); }

    @Override
    public void invokeInEdtAndWait(Runnable proc) {
        if (isGuiThread()) {
            proc.run();
            return;
        }
        try {
            ForgeEngine.UI.submit(proc).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    @Override
    public void runBackgroundTask(String message, Runnable task) {
        Thread t = new Thread(task, "Game MTGCraft background");
        t.setDaemon(true);
        t.start();
    }

    @Override public boolean isGuiThread() { return ForgeEngine.isUiThread(); }
    @Override public String getAssetsDir() { return assetsDir; }
    @Override public ImageFetcher getImageFetcher() { return imageFetcher; }
    @Override public ISkinImage getSkinIcon(FSkinProp skinProp) { return null; }
    @Override public ISkinImage getUnskinnedIcon(String path) { return null; }
    @Override public ISkinImage getCardArt(PaperCard card, boolean backFace) { return null; }
    @Override public ISkinImage createLayeredImage(PaperCard card, FSkinProp background, String overlayFilename, float opacity) { return null; }
    @Override public void clearImageCache() { }
    @Override public String encodeSymbols(String str, boolean formatReminderText) { return str; }
    @Override public int getAvatarCount() { return 0; }
    @Override public int getSleevesCount() { return 0; }
    @Override public float getScreenScale() { return 1.0f; }
    @Override public void preventSystemSleep(boolean preventSleep) { }
    @Override public void download(GuiDownloadService service, Consumer<Boolean> callback) { callback.accept(false); }
    @Override public void copyToClipboard(String text) { }
    @Override public void browseToUrl(String url) { }
    @Override public void showCardList(String title, String message, List<PaperCard> list) { }
    @Override public boolean showBoxedProduct(String title, String message, List<PaperCard> list) { return false; }
    @Override public void showBugReportDialog(String title, String text, boolean showExitAppBtn) { System.err.println("[MTGCraft] " + title + "\n" + text); }
    @Override public void showImageDialog(ISkinImage image, String message, String title) { }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        DuelGui gui = DuelGui.active();
        return gui == null ? defaultOption : gui.showOptionDialog(message, title, icon, options, defaultOption);
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) {
        DuelGui gui = DuelGui.active();
        return gui == null ? initialInput : gui.showInputDialog(message, title, icon, initialInput, inputOptions, isNumeric);
    }

    @Override public String showFileDialog(String title, String defaultDir) { return null; }
    @Override public File getSaveFile(File defaultFile) { return defaultFile; }

    @Override
    public <T> List<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax, List<T> sourceChoices, List<T> destChoices) {
        DuelGui gui = DuelGui.active();
        if (gui == null) {
            List<T> all = new ArrayList<>(sourceChoices);
            if (destChoices != null) all.addAll(destChoices);
            return all;
        }
        return gui.order(title, top, remainingObjectsMin, remainingObjectsMax, sourceChoices, destChoices, null, false, false).ordered();
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, Collection<T> choices, Collection<T> selected, FSerializableFunction<T, String> display) {
        DuelGui gui = DuelGui.active();
        if (gui == null) {
            return selected == null ? new ArrayList<>() : new ArrayList<>(selected);
        }
        return gui.getChoices(message, min, max, new ArrayList<>(choices), selected == null ? null : new ArrayList<>(selected), display);
    }

    @Override
    public PaperCard chooseCard(String title, String message, List<PaperCard> list) {
        DuelGui gui = DuelGui.active();
        if (gui == null || list.isEmpty()) {
            return list.isEmpty() ? null : list.get(0);
        }
        return gui.one(title, list, PaperCard::getName);
    }

    @Override public boolean isSupportedAudioFormat(File file) { return false; }
    @Override public IAudioClip createAudioClip(String filename) { return null; }
    @Override public IAudioMusic createAudioMusic(String filename) { return null; }
    @Override public void startAltSoundSystem(String filename, boolean isSynchronized) { }
    @Override public void showSpellShop() { }
    @Override public void showBazaar() { }
    @Override public IGuiGame getNewGuiGame() { return new DuelGui(); }
    @Override public HostedMatch hostMatch() { return new HostedMatch(); }
    @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
    @Override public boolean hasNetGame() { return false; }
}
