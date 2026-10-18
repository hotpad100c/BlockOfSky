package ml.mypals.sky.smoke;

import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Saves the textures of the frames rendered while active, for the write-up. Development only. */
public final class Capture {
    private static boolean active;
    private static String tag = "run";

    private Capture() {
    }

    public static boolean active() {
        return active;
    }

    static void start(String runTag) {
        tag = runTag;
        active = true;
    }

    /** Called by the last capture of a frame: one whole frame is enough. */
    public static void frameDone() {
        active = false;
    }

    /** A colour texture, clamped to [0, 1]. */
    public static void color(int texture, String name) {
        int[] size = size(texture);
        FloatBuffer pixels = read(texture, GL11.GL_RGBA, size, 4);
        BufferedImage image = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size[1]; y++) {
            for (int x = 0; x < size[0]; x++) {
                int i = ((size[1] - 1 - y) * size[0] + x) * 4;
                image.setRGB(x, y, channel(pixels.get(i)) << 16 | channel(pixels.get(i + 1)) << 8 | channel(pixels.get(i + 2)));
            }
        }
        save(image, name);
    }

    /** A depth texture, or a red float texture holding depths: near is white, far is black, the far plane dark blue. */
    public static void depth(int texture, boolean depthTexture, String name) {
        int[] size = size(texture);
        FloatBuffer depths = read(texture, depthTexture ? GL11.GL_DEPTH_COMPONENT : GL11.GL_RED, size, 1);
        BufferedImage image = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size[1]; y++) {
            for (int x = 0; x < size[0]; x++) {
                image.setRGB(x, y, depthColor(depths.get((size[1] - 1 - y) * size[0] + x)));
            }
        }
        save(image, name);
    }

    /** White where the two red float textures differ. */
    public static void difference(int first, int second, String name) {
        int[] size = size(first);
        FloatBuffer a = read(first, GL11.GL_RED, size, 1);
        FloatBuffer b = read(second, GL11.GL_RED, size, 1);
        BufferedImage image = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size[1]; y++) {
            for (int x = 0; x < size[0]; x++) {
                int i = (size[1] - 1 - y) * size[0] + x;
                image.setRGB(x, y, a.get(i) != b.get(i) ? 0xFFFFFF : 0x202020);
            }
        }
        save(image, name);
    }

    private static int depthColor(float depth) {
        if (depth >= 1.0F) {
            return 0x102040;
        }
        float near = 0.05F;
        float far = Minecraft.getInstance().gameRenderer.getDepthFar();
        float distance = 2.0F * near * far / (far + near - (2.0F * depth - 1.0F) * (far - near));
        int gray = channel(1.0F - Math.min(distance / 24.0F, 1.0F));
        return gray << 16 | gray << 8 | gray;
    }

    private static int channel(float value) {
        return Math.round(Math.max(0.0F, Math.min(1.0F, value)) * 255.0F);
    }

    private static int[] size(int texture) {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        int[] size = {GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH),
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT)};
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        return size;
    }

    private static FloatBuffer read(int texture, int format, int[] size, int channels) {
        FloatBuffer pixels = BufferUtils.createFloatBuffer(size[0] * size[1] * channels);
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int pack = GL11.glGetInteger(GL30.GL_PIXEL_PACK_BUFFER_BINDING);
        GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, format, GL11.GL_FLOAT, pixels);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        GL30.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, pack);
        return pixels;
    }

    private static void save(BufferedImage image, String name) {
        try {
            Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("captures").resolve(tag);
            Files.createDirectories(dir);
            ImageIO.write(image, "png", dir.resolve(name + ".png").toFile());
        } catch (IOException e) {
            System.out.println("[sky-smoke] could not save " + name + ": " + e);
        }
    }
}
