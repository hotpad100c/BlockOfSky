package ml.mypals.sky.smoke;

import com.mojang.renderpearl.api.textures.GpuTexture;
import ml.mypals.sky.client.ShadowHoles;
import ml.mypals.sky.client.ThroughDepth;
import ml.mypals.sky.client.iris.IrisAccess;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Saves the shadow map, the sky blocks' depth maps and (in the receiver mode) the depth beyond the blocks as grey
 * images, and logs what is in them. All of a shot share the grey scale of the shadow map, so they compare: as in the
 * write-up's depth pictures, nearer (the sun) is whiter, farther is darker, nothing (the far depth) is dark blue.
 */
final class DepthDump {
    private DepthDump() {
    }

    static void dump(Minecraft mc, String shot) {
        Matrix4f mv = ShadowRenderer.MODELVIEW;
        if (mv != null) {
            log("sun direction (world) = " + mv.m02() + ", " + mv.m12() + ", " + mv.m22());
        }
        float[] shadow = read(IrisAccess.shadowDepth());
        if (shadow == null) {
            log("shadowtex0: none");
            return;
        }
        float[] range = range(shadow);
        save(mc, shot, "shadowtex0", shadow, range);
        String[] names = {"frontNear", "throughNear", "far"};
        for (int i = 0; i < 3; i++) {
            float[] map = read(ShadowHoles.map(i));
            if (map != null) {
                save(mc, shot, names[i], map, range);
            }
        }
        if (ThroughDepth.enabled() && ThroughDepth.glId() != 0) {
            save(mc, shot, "throughDepth", readThrough(shadow.length), range);
        }
    }

    private static float[] read(GpuTexture texture) {
        if (texture == null) {
            return null;
        }
        int size = texture.getWidth(0);
        FloatBuffer depth = BufferUtils.createFloatBuffer(size * size);
        resetPack();
        GL45.glGetTextureImage(((GpuTextureInterface) texture).iris$getGlId(), 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
        float[] out = new float[size * size];
        depth.get(out);
        return out;
    }

    /** The depth beyond the blocks: float bits kept as unsigned integers (see {@code ThroughDepth}). */
    private static float[] readThrough(int texels) {
        IntBuffer bits = BufferUtils.createIntBuffer(texels);
        resetPack();
        GL45.glGetTextureImage(ThroughDepth.glId(), 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, bits);
        float[] out = new float[texels];
        for (int i = 0; i < texels; i++) {
            out[i] = Float.intBitsToFloat(bits.get(i));
        }
        return out;
    }

    /** The game leaves its own pixel pack state behind (row length, a bound pack buffer). */
    private static void resetPack() {
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
    }

    /** The grey scale: from the 1st to the 99th percentile of what is nearer than the far depth, in the middle crop. */
    private static float[] range(float[] depth) {
        int size = (int) Math.sqrt(depth.length);
        int crop = Math.min(size, 512);
        int start = (size - crop) / 2;
        float[] near = new float[crop * crop];
        int n = 0;
        for (int y = start; y < start + crop; y++) {
            for (int x = start; x < start + crop; x++) {
                float d = depth[y * size + x];
                if (d < 1.0F) {
                    near[n++] = d;
                }
            }
        }
        if (n == 0) {
            return new float[]{0.0F, 1.0F};
        }
        Arrays.sort(near, 0, n);
        float lo = near[n / 100];
        return new float[]{lo, Math.max(near[n * 99 / 100], lo + 1.0E-6F)};
    }

    private static void save(Minecraft mc, String shot, String name, float[] depth, float[] range) {
        int size = (int) Math.sqrt(depth.length);
        int notFar = 0;
        for (float d : depth) {
            if (d < 1.0F) {
                notFar++;
            }
        }
        log(name + ": " + size + "x" + size + ", texels below 1: " + notFar + ", centre: " + depth[size / 2 * size + size / 2]);
        Path dir = mc.gameDirectory.toPath().resolve("screenshots");
        try {
            Files.createDirectories(dir);
            ByteBuffer raw = ByteBuffer.allocate(depth.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            raw.asFloatBuffer().put(depth);
            Files.write(dir.resolve("depth-" + shot + "-" + name + ".f32"), raw.array());
        } catch (IOException e) {
            log("could not save raw " + name + ": " + e);
        }
        // Only the middle of the map: the shadow map is centred on the camera.
        int crop = Math.min(size, 512);
        int start = (size - crop) / 2;
        float lo = range[0];
        float hi = range[1];
        BufferedImage image = new BufferedImage(crop, crop, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < crop; y++) {
            for (int x = 0; x < crop; x++) {
                float d = depth[(start + crop - 1 - y) * size + start + x];
                int g = (int) Math.max(0, Math.min(255, 30 + 220 * (hi - d) / (hi - lo)));
                image.setRGB(x, y, d >= 1.0F ? 0x203060 : g << 16 | g << 8 | g);
            }
        }
        try {
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
