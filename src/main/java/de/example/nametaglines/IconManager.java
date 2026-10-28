package de.example.nametaglines;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads all PNG and GIF files from config/nametaglines/icons as dynamic textures.
 * In a line's text, an icon is inserted with {icon:filename} (without extension, lowercase).
 *
 * Animated GIFs are decoded into a "sprite sheet" (all frames stacked vertically in one texture);
 * the renderers pick the frame that matches the current time.
 */
public final class IconManager {
    private static final int MAX_FRAMES = 200;
    private static final int MAX_SHEET_SIZE = 8192; // max texture width/height in pixels

    /**
     * @param width    width of one frame
     * @param height   height of one frame
     * @param frames   number of frames (1 for PNG)
     * @param delaysMs display time of each frame in milliseconds
     */
    public record Icon(String name, Identifier id, int width, int height, int frames, int[] delaysMs) {
        /** Index of the frame that should be shown right now. */
        public int currentFrame() {
            if (frames <= 1) return 0;
            long total = 0;
            for (int d : delaysMs) total += d;
            if (total <= 0) return 0;
            long t = System.currentTimeMillis() % total;
            for (int i = 0; i < frames; i++) {
                if (t < delaysMs[i]) return i;
                t -= delaysMs[i];
            }
            return frames - 1;
        }

        /** Height of the whole texture (all frames stacked). */
        public int sheetHeight() {
            return height * frames;
        }
    }

    private static final Map<String, Icon> ICONS = new LinkedHashMap<>();

    private IconManager() {}

    public static Path folder() {
        return FabricLoader.getInstance().getConfigDir().resolve("nametaglines").resolve("icons");
    }

    public static Icon get(String name) {
        return ICONS.get(name);
    }

    public static Collection<Icon> all() {
        return ICONS.values();
    }

    /** Must run on the render thread (client start, button, command). */
    public static void reload() {
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        for (Icon icon : ICONS.values()) {
            textures.release(icon.id());
        }
        ICONS.clear();

        try {
            Files.createDirectories(folder());
        } catch (Exception e) {
            NametagLinesClient.LOGGER.warn("Could not create icon folder", e);
            return;
        }

        try (Stream<Path> files = Files.list(folder())) {
            files.filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".png") || n.endsWith(".gif");
                    })
                    .sorted()
                    .forEach(p -> load(textures, p));
        } catch (Exception e) {
            NametagLinesClient.LOGGER.warn("Could not read icons", e);
        }
        NametagLinesClient.LOGGER.info("{} icon(s) loaded", ICONS.size());
    }

    private static void load(TextureManager textures, Path path) {
        String file = path.getFileName().toString();
        String name = sanitize(file.substring(0, file.length() - 4));
        if (name.isEmpty()) return;

        try {
            NativeImage image;
            int w;
            int h;
            int frames = 1;
            int[] delays = {1000};

            if (file.toLowerCase(Locale.ROOT).endsWith(".gif")) {
                GifData gif = readGif(path);
                image = NativeImage.read(new ByteArrayInputStream(gif.sheetPng()));
                w = gif.width();
                h = gif.height();
                frames = gif.frames();
                delays = gif.delaysMs();
            } else {
                try (InputStream in = Files.newInputStream(path)) {
                    image = NativeImage.read(in);
                }
                w = image.getWidth();
                h = image.getHeight();
            }
            if (w <= 0 || h <= 0) return;

            Identifier id = Identifier.fromNamespaceAndPath(NametagLinesClient.MOD_ID, "icons/" + name);
            textures.register(id, new DynamicTexture(() -> "nametaglines/" + name, image));
            ICONS.put(name, new Icon(name, id, w, h, frames, delays));
        } catch (Throwable e) {
            // Throwable on purpose: GIF decoding needs java.desktop, which could be missing in a minimal runtime
            NametagLinesClient.LOGGER.warn("Could not load icon {}", file, e);
        }
    }

    // ---------------- GIF decoding ----------------

    private record GifData(byte[] sheetPng, int width, int height, int frames, int[] delaysMs) {}

    private static GifData readGif(Path path) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) throw new IOException("no GIF reader available");
        ImageReader reader = readers.next();

        try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
            reader.setInput(in, false);
            int count = reader.getNumImages(true);
            if (count <= 0) throw new IOException("GIF has no frames");

            // Size of the whole picture (frames can be smaller sub-rectangles)
            int width = 0;
            int height = 0;
            try {
                IIOMetadata streamMeta = reader.getStreamMetadata();
                IIOMetadataNode root = (IIOMetadataNode) streamMeta.getAsTree("javax_imageio_gif_stream_1.0");
                IIOMetadataNode lsd = child(root, "LogicalScreenDescriptor");
                if (lsd != null) {
                    width = Integer.parseInt(lsd.getAttribute("logicalScreenWidth"));
                    height = Integer.parseInt(lsd.getAttribute("logicalScreenHeight"));
                }
            } catch (Exception ignored) {
                // fall back to the first frame's size below
            }
            if (width <= 0 || height <= 0) {
                BufferedImage first = reader.read(0);
                width = first.getWidth();
                height = first.getHeight();
            }
            if (width > MAX_SHEET_SIZE || height > MAX_SHEET_SIZE) throw new IOException("GIF too large");

            int maxFrames = Math.max(1, Math.min(MAX_FRAMES, MAX_SHEET_SIZE / height));
            int n = Math.min(count, maxFrames);

            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            List<BufferedImage> frames = new ArrayList<>();
            int[] delays = new int[n];

            for (int i = 0; i < n; i++) {
                BufferedImage frame = reader.read(i);

                int left = 0;
                int top = 0;
                String disposal = "none";
                int delay = 10; // 1/100 s
                try {
                    IIOMetadata meta = reader.getImageMetadata(i);
                    IIOMetadataNode tree = (IIOMetadataNode) meta.getAsTree("javax_imageio_gif_image_1.0");
                    IIOMetadataNode desc = child(tree, "ImageDescriptor");
                    if (desc != null) {
                        left = Integer.parseInt(desc.getAttribute("imageLeftPosition"));
                        top = Integer.parseInt(desc.getAttribute("imageTopPosition"));
                    }
                    IIOMetadataNode gce = child(tree, "GraphicControlExtension");
                    if (gce != null) {
                        disposal = gce.getAttribute("disposalMethod");
                        delay = Integer.parseInt(gce.getAttribute("delayTime"));
                    }
                } catch (Exception ignored) {
                    // keep defaults
                }
                int ms = delay * 10;
                delays[i] = ms < 20 ? 100 : ms; // like browsers: tiny delays are treated as 100 ms

                BufferedImage before = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;

                Graphics2D g = canvas.createGraphics();
                g.drawImage(frame, left, top, null);
                g.dispose();
                frames.add(copy(canvas));

                // Prepare the canvas for the next frame
                if ("restoreToBackgroundColor".equals(disposal)) {
                    Graphics2D c = canvas.createGraphics();
                    c.setComposite(AlphaComposite.Clear);
                    c.fillRect(left, top, frame.getWidth(), frame.getHeight());
                    c.dispose();
                } else if (before != null) {
                    canvas = before;
                }
            }

            // Stack all frames vertically into one sprite sheet and encode it as PNG
            BufferedImage sheet = new BufferedImage(width, height * n, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = sheet.createGraphics();
            for (int i = 0; i < n; i++) {
                g.drawImage(frames.get(i), 0, i * height, null);
            }
            g.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(sheet, "png", out);
            return new GifData(out.toByteArray(), width, height, n, delays);
        } finally {
            reader.dispose();
        }
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    private static IIOMetadataNode child(IIOMetadataNode parent, String name) {
        NodeList list = parent.getElementsByTagName(name);
        return list.getLength() > 0 ? (IIOMetadataNode) list.item(0) : null;
    }

    /** Only a-z, 0-9, _ and - (valid identifier path and valid token). */
    public static String sanitize(String raw) {
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
    }
}
