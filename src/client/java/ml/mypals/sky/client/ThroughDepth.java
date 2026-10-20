package ml.mypals.sky.client;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class ThroughDepth {
    public static final String IMAGE = "blockofsky_throughImage";
    public static final String SAMPLER = "blockofsky_throughDepth";
    public static final Logger LOGGER = LoggerFactory.getLogger("sky");
    private static final int CLEAR = Float.floatToRawIntBits(1.0F);

    private static boolean enabled;
    private static int texture;
    private static int framebuffer;
    private static int size;

    private ThroughDepth() {
    }

    public static void detect() {
        if (!ShadowHoles.enabled()) {
            LOGGER.info("Shadow holes: off (sky.shadowHoles=false)");
            return;
        }
        if (!"receiver".equals(System.getProperty("sky.shadowHoles.mode"))) {
            LOGGER.info("Shadow holes: maps mode");
            return;
        }
        GLCapabilities caps = GL.getCapabilities();
        List<String> missing = new ArrayList<>();
        if (!caps.OpenGL42 && !caps.GL_ARB_shader_image_load_store) {
            missing.add("GL_ARB_shader_image_load_store");
        }
        if (caps.glBindImageTexture == 0L || caps.glMemoryBarrier == 0L) {
            missing.add("glBindImageTexture/glMemoryBarrier");
        }
        String context = "OpenGL " + GL11.glGetString(GL11.GL_VERSION) + ", GLSL " + GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION)
                + ", " + GL11.glGetString(GL11.GL_RENDERER);
        enabled = missing.isEmpty();
        if (enabled) {
            LOGGER.info("Shadow holes: receiver mode ({})", context);
        } else {
            LOGGER.warn("Shadow holes: the receiver mode needs {}, missing on {}; using the maps mode", missing, context);
        }
    }

    public static boolean enabled() {
        return enabled && ShadowHoles.enabled();
    }

    public static int glId() {
        return texture;
    }

    static void clear(int resolution) {
        if (texture == 0 || size != resolution) {
            close();
            size = resolution;
            int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32UI, resolution, resolution, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT,
                    (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
            if (framebuffer == 0) {
                framebuffer = GL30.glGenFramebuffers();
            }
        }
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer mask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glColorMask(true, true, true, true);
            GL30.glClearBufferuiv(GL11.GL_COLOR, 0, new int[]{CLEAR, 0, 0, 0});
            GL11.glColorMask(mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
        }
        if (scissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
    }

    public static void afterShadowPass() {
        if (texture != 0) {
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        }
    }

    static void close() {
        if (texture != 0) {
            GlStateManager._deleteTexture(texture);
            texture = 0;
        }
        if (framebuffer != 0) {
            GL30.glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
    }
}
