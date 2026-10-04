package dev.mtgcraft.engine;

import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.util.BuildInfo;
import forge.util.ImageFetcher;
import forge.util.ScryfallRateLimiter;
import forge.util.TextUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Downloads card images into Card-Forge's cache folder. Card-Forge decides the URLs (Scryfall) and the cache
 * file names; this only does the HTTP part. Adapted from Card-Forge's LibGDXImageFetcher (GPL-3.0).
 */
final class McImageFetcher extends ImageFetcher {
    @Override
    protected Runnable getDownloadTask(String[] downloadUrls, String destPath, Runnable notifyObservers) {
        return () -> {
            for (String url : downloadUrls) {
                try {
                    if (fetch(url.replace("PLANECHASEBG:", ""), destPath)) {
                        GuiBase.getInterface().invokeInEdtLater(notifyObservers);
                        return;
                    }
                } catch (Exception e) {
                    System.err.println("[MTGCraft] Image download failed " + url + ": " + e.getMessage());
                }
            }
        };
    }

    private static boolean fetch(String urlToDownload, String destPath) throws IOException {
        if (disableHostedDownload && urlToDownload.startsWith(ForgeConstants.URL_CARDFORGE)) {
            // Card-Forge's own image host is offline; go straight to the Scryfall URLs.
            return false;
        }
        if (ScryfallRateLimiter.shouldSkip(urlToDownload)) {
            return false;
        }
        boolean isScryfallUrl = urlToDownload.startsWith(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD)
                || urlToDownload.startsWith(ForgeConstants.URL_SCRYFALL_CDN);
        // Scryfall images have borders; Card-Forge names those files *.fullborder.jpg.
        String dest = urlToDownload.contains(".fullborder.") || isScryfallUrl
                ? TextUtil.fastReplace(destPath, ".full.", ".fullborder.") : destPath;
        if (!dest.contains(".full") && isScryfallUrl && !destPath.startsWith(ForgeConstants.CACHE_TOKEN_PICS_DIR)
                && !destPath.startsWith(ForgeConstants.CACHE_PLANECHASE_PICS_DIR)) {
            dest = dest.replace(".jpg", ".fullborder.jpg");
        }

        ScryfallRateLimiter.acquire(urlToDownload);
        HttpURLConnection c = (HttpURLConnection) new URL(urlToDownload).openConnection();
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("User-Agent", BuildInfo.getUserAgent());
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                if (code == 429 && ScryfallRateLimiter.isApiUrl(urlToDownload)) {
                    ScryfallRateLimiter.noteIfRateLimited(code, urlToDownload, c.getHeaderField("Retry-After"));
                }
                return false;
            }
            Path out = Path.of(dest);
            Files.createDirectories(out.getParent());
            Path tmp = Path.of(dest + ".tmp");
            try (InputStream is = c.getInputStream()) {
                Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(tmp) == 0) {
                Files.delete(tmp);
                return false;
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } finally {
            c.disconnect();
        }
    }
}
