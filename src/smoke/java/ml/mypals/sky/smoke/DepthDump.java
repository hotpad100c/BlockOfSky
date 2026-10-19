package ml.mypals.sky.smoke;

import com.mojang.renderpearl.api.textures.GpuTexture;
import ml.mypals.sky.client.ShadowHoles;
import ml.mypals.sky.client.iris.IrisAccess;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL45;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Saves the shadow map and the sky blocks' depth maps as grey images, and logs what is in them. */
final class DepthDump {
    private DepthDump() {
    }

    static void dump(Minecraft mc, String shot) {
        Matrix4f mv = ShadowRenderer.MODELVIEW;
        if (mv != null) {
            log("sun direction (world) = " + mv.m02() + ", " + mv.m12() + ", " + mv.m22());
        }
        save(mc, shot, "shadowtex0", IrisAccess.shadowDepth());
        String[] names = {"frontNear", "throughNear", "far"};
        for (int i = 0; i < 3; i++) {
            save(mc, shot, names[i], ShadowHoles.map(i));
        }
    }

    private static void save(Minecraft mc, String shot, String name, GpuTexture texture) {
        if (texture == null) {
            log(name + ": none");
            return;
        }
        int size = texture.getWidth(0);
        FloatBuffer depth = BufferUtils.createFloatBuffer(size * size);
        // The game leaves its own pixel pack state behind (row length, a bound pack buffer).
        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL45.glGetTextureImage(((GpuTextureInterface) texture).iris$getGlId(), 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
        try {
            Path dir = mc.gameDirectory.toPath().resolve("screenshots");
            Files.createDirectories(dir);
            java.nio.ByteBuffer raw = java.nio.ByteBuffer.allocate(size * size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            raw.asFloatBuffer().put(depth.duplicate());
            Files.write(dir.resolve("depth-" + shot + "-" + name + ".f32"), raw.array());
        } catch (IOException e) {
            log("could not save raw " + name + ": " + e);
        }
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        int notFar = 0;
        for (int i = 0; i < size * size; i++) {
            float d = depth.get(i);
            min = Math.min(min, d);
            max = Math.max(max, d);
            if (d < 1.0F) {
                notFar++;
            }
        }
        float[] sorted = new float[notFar];
        int n = 0;
        int zeros = 0;
        for (int i = 0; i < size * size; i++) {
            float d = depth.get(i);
            if (d == 0.0F) {
                zeros++;
            }
            if (d < 1.0F) {
                sorted[n++] = d;
            }
        }
        java.util.Arrays.sort(sorted);
        String pct = n == 0 ? "-" : sorted[0] + " / " + sorted[n / 100] + " / " + sorted[n / 2] + " / " + sorted[n * 99 / 100] + " / " + sorted[n - 1];
        log(name + ": " + size + "x" + size + ", texels below 1: " + notFar + ", exactly 0: " + zeros
                + ", min/p1/p50/p99/max below 1: " + pct + ", centre: " + depth.get(size / 2 * size + size / 2));
        if (n > 0) {
            min = sorted[n / 100];
            max = sorted[n * 99 / 100];
        }
        // Only the middle of the map: the shadow map is centred on the camera.
        int crop = Math.min(size, 512);
        int start = (size - crop) / 2;
        float lo = min;
        float hi = Math.max(max, lo + 1.0E-6F);
        BufferedImage image = new BufferedImage(crop, crop, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < crop; y++) {
            for (int x = 0; x < crop; x++) {
                float d = depth.get((start + crop - 1 - y) * size + start + x);
                int g = d >= 1.0F ? 255 : (int) Math.max(0, Math.min(255, 30 + 220 * (d - lo) / (hi - lo)));
                image.setRGB(x, y, d >= 1.0F ? 0x203060 : g << 16 | g << 8 | g);
            }
        }
        try {
            Path dir = mc.gameDirectory.toPath().resolve("screenshots");
            Files.createDirectories(dir);
            ImageIO.write(image, "png", dir.resolve("depth-" + shot + "-" + name + ".png").toFile());
        } catch (IOException e) {
            log("could not save " + name + ": " + e);
        }
    }

    private static void log(String message) {
        System.out.println("[sky-smoke] " + message);
        System.out.flush();
    }
}
