package com.teammate.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.teammate.TeammateMod;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Downloads the team icon (PNG, up to 512x512) from the leader-provided URL on a
 * background thread and registers it as a dynamic texture. Results are cached per
 * URL; failing URLs are only tried once per session.
 */
public final class TeamIconManager {
    public record Icon(ResourceLocation location, int width, int height) {
    }

    private static final int MAX_BYTES = 8 * 1024 * 1024; // enough headroom for a 512x512 PNG
    private static final int MAX_DIMENSION = 512;
    private static final Map<String, Icon> LOADED = new ConcurrentHashMap<>();
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private TeamIconManager() {
    }

    /** Returns the icon if it is ready, otherwise kicks off a download and returns null. */
    @Nullable
    public static Icon get(@Nullable String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        Icon icon = LOADED.get(url);
        if (icon == null) {
            request(url);
        }
        return icon;
    }

    public static void request(String url) {
        if (url.isEmpty() || LOADED.containsKey(url) || FAILED.contains(url) || !IN_FLIGHT.add(url)) {
            return;
        }
        Util.backgroundExecutor().execute(() -> download(url));
    }

    private static void download(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "Minecraft-Teammate-Mod");
            try (InputStream in = connection.getInputStream()) {
                // read one byte past the cap so an oversized file is detected instead of
                // silently truncated (which would otherwise hand NativeImage a corrupt PNG)
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    throw new IllegalArgumentException("file exceeds " + MAX_BYTES + " bytes");
                }
                NativeImage image = NativeImage.read(bytes);
                if (image.getWidth() > MAX_DIMENSION || image.getHeight() > MAX_DIMENSION) {
                    image.close();
                    throw new IllegalArgumentException("image exceeds " + MAX_DIMENSION + "x" + MAX_DIMENSION);
                }
                Minecraft.getInstance().execute(() -> {
                    ResourceLocation location = ResourceLocation.fromNamespaceAndPath(TeammateMod.MODID,
                            "team_icon_" + NEXT_ID.getAndIncrement());
                    Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
                    LOADED.put(url, new Icon(location, image.getWidth(), image.getHeight()));
                    IN_FLIGHT.remove(url);
                });
            }
        } catch (Exception e) {
            TeammateMod.LOGGER.warn("Failed to load team icon from {}: {}", url, e.toString());
            FAILED.add(url);
            IN_FLIGHT.remove(url);
        }
    }
}
