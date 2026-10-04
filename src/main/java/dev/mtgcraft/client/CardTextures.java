package dev.mtgcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.mtgcraft.MtgCraft;
import dev.mtgcraft.engine.ForgeEngine;
import forge.ImageKeys;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.item.PaperToken;
import forge.util.ImageUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Card art as GPU textures. Images come from Card-Forge's cache, or are downloaded from Scryfall by its image
 * fetcher. Each card gets a small texture for the board and a large one for the zoom view; both use linear
 * filtering so moving and scaled cards stay smooth.
 */
public final class CardTextures {
    public static final int SMALL_W = 146, SMALL_H = 204;
    public static final int LARGE_W = 488, LARGE_H = 680;

    private enum Status { LOADING, READY, MISSING }

    private static final class Entry {
        volatile Status status = Status.LOADING;
        ResourceLocation small, large;
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static final ExecutorService DECODER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "MTGCraft image decode");
        t.setDaemon(true);
        return t;
    });
    private static int counter;

    private CardTextures() {}

    /** The texture for an image key, or null while it's still loading (or has no image). Render thread only. */
    public static ResourceLocation get(String key, boolean large) {
        if (key == null || key.length() < 3) return null;
        Entry e = CACHE.get(key);
        if (e == null) {
            e = new Entry();
            CACHE.put(key, e);
            request(key, e);
        }
        if (e.status != Status.READY) return null;
        return large ? e.large : e.small;
    }

    private static void request(String key, Entry e) {
        DECODER.execute(() -> {
            File f = lookup(key, false);
            if (f != null && f.isFile()) {
                decode(key, e, f);
                return;
            }
            // Not cached yet: Card-Forge works out the Scryfall URL and downloads it on its UI thread.
            ForgeEngine.runOnUi(() -> GuiBase.getInterface().getImageFetcher().fetchImage(key, () ->
                    DECODER.execute(() -> {
                        File got = lookup(key, true);
                        if (got != null && got.isFile()) decode(key, e, got);
                        else e.status = Status.MISSING;
                    })));
        });
    }

    /**
     * Card-Forge's image lookup keeps unsynchronized caches, so all lookups go through one lock. The first lookup
     * records a not-yet-downloaded image as missing; after a download that record must be cleared.
     */
    private static File lookup(String key, boolean afterDownload) {
        synchronized (ImageKeys.class) {
            if (afterDownload) ImageKeys.clearMissingCards();
            String fileKey = fileKey(key);
            return fileKey == null ? null : ImageKeys.getImageFile(fileKey);
        }
    }

    /**
     * Turns a game image key ("c:Name|SET|art", "t:token...") into the cache file key ("SET/Name.full")
     * that ImageKeys.getImageFile expects. Same conversion as Card-Forge's mobile ImageCache.
     */
    private static String fileKey(String key) {
        boolean back = key.endsWith(ImageKeys.BACKFACE_POSTFIX);
        String k = back ? key.substring(0, key.length() - ImageKeys.BACKFACE_POSTFIX.length()) : key;
        if (k.startsWith(ImageKeys.CARD_PREFIX)) {
            PaperCard card = ImageUtil.getPaperCardFromImageKey(k);
            if (card == null) return null;
            return back ? card.getCardAltImageKey() : card.getCardImageKey();
        }
        if (k.startsWith(ImageKeys.TOKEN_PREFIX)) {
            PaperToken token = ImageUtil.getPaperTokenFromImageKey(k);
            return token == null ? k : token.getCardImageKey();
        }
        return k;
    }

    private static void decode(String key, Entry e, File file) {
        try {
            BufferedImage src = ImageIO.read(file);
            if (src == null) {
                e.status = Status.MISSING;
                return;
            }
            NativeImage small = toNative(scale(src, SMALL_W, SMALL_H));
            NativeImage large = toNative(scale(src, LARGE_W, LARGE_H));
            Minecraft.getInstance().execute(() -> {
                int id = counter++;
                e.small = register("card_s" + id, small);
                e.large = register("card_l" + id, large);
                e.status = Status.READY;
            });
        } catch (Exception ex) {
            System.err.println("[MTGCraft] Could not load card image " + file + ": " + ex);
            e.status = Status.MISSING;
        }
    }

    private static ResourceLocation register(String name, NativeImage image) {
        DynamicTexture tex = new DynamicTexture(image);
        tex.setFilter(true, false);
        ResourceLocation rl = new ResourceLocation(MtgCraft.MODID, "dynamic/" + name);
        Minecraft.getInstance().getTextureManager().register(rl, tex);
        return rl;
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Scryfall scans have rounded corners on white/black; clip to a rounded card shape.
        g.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, w, h, w * 0.09f, w * 0.09f));
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static NativeImage toNative(BufferedImage img) {
        NativeImage n = new NativeImage(img.getWidth(), img.getHeight(), true);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                n.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return n;
    }
}
