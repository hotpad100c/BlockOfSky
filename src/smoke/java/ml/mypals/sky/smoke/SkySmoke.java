package ml.mypals.sky.smoke;

import ml.mypals.sky.smoke.mixin.CreateWorldScreenAccessor;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Development check, not part of the mod. It creates a creative world, builds a wall of sky blocks with stone behind
 * part of it and a stone and a glass block in front of it, holds a block in the hand, takes screenshots from a few views
 * and quits. Run it with {@code ./gradlew runSmokeClient}. Screenshots land in {@code run/smoke/screenshots}, prefixed
 * with the {@code -PsmokeTag} Gradle property (default {@code run}).
 */
@Mod("sky_smoke")
public final class SkySmoke {
    private static final String TAG = "[sky-smoke] ";
    private static final String WORLD = "Sky Smoke";
    private static final int TIMEOUT_TICKS = 20 * 60 * 5;

    private final String tag = System.getProperty("sky.smoke.tag", "run");
    private int ticks;
    private int ticksInWorld;
    private int builtAt = -1;
    private boolean requestedScreen;
    private boolean created;
    private boolean done;
    private BlockPos origin;
    private Vec3 home;

    public SkySmoke() {
        deleteOldWorld();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> this.tick(Minecraft.getInstance()));
    }

    private void tick(Minecraft mc) {
        if (this.done) {
            return;
        }
        this.ticks++;
        if (this.ticks == 1) {
            mc.options.pauseOnLostFocus = false;
        }
        if (mc.level != null && mc.player != null) {
            this.ticksInWorld++;
            this.showcase(mc);
            return;
        }
        if (this.ticks > TIMEOUT_TICKS) {
            this.done = true;
            log("RESULT: FAILED (no world after " + TIMEOUT_TICKS + " ticks)");
            Runtime.getRuntime().halt(2);
        }
        if (this.created) {
            return;
        }
        if (mc.screen instanceof CreateWorldScreen screen) {
            WorldCreationUiState state = screen.getUiState();
            state.setName(WORLD);
            state.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            log("creating the world");
            this.created = true;
            ((CreateWorldScreenAccessor) screen).sky_smoke$onCreate();
            return;
        }
        if (!this.requestedScreen && mc.getOverlay() == null && this.ticks > 40) {
            this.requestedScreen = true;
            CreateWorldScreen.openFresh(mc, null);
        }
    }

    private void showcase(Minecraft mc) {
        int t = this.ticksInWorld;
        if (t == 20) {
            command(mc, "time set 1000");
            command(mc, "weather clear");
            command(mc, "gamerule doDaylightCycle false");
            command(mc, "tp @s ~ 200 ~ 0 0");
            command(mc, "item replace entity @s weapon.mainhand with minecraft:stone");
        }
        if (this.builtAt < 0 && t > 20 && mc.player.getY() > 190) {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
            BlockPos feet = mc.player.blockPosition();
            int eye = (int) Math.floor(mc.player.getEyeY());
            this.origin = new BlockPos(feet.getX(), eye, feet.getZ());
            this.home = mc.player.position();
            int x = this.origin.getX();
            int z = this.origin.getZ();
            // A closed stone room lit by glowstone. Its far wall has a window of sky blocks with solid stone behind it,
            // so any sky seen in the window comes from the sky blocks.
            command(mc, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone hollow", x - 6, eye - 3, z - 2, x + 6, eye + 3, z + 8));
            command(mc, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:glowstone", x - 4, eye + 3, z, x + 4, eye + 3, z));
            command(mc, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:glowstone", x - 4, eye + 3, z + 6, x + 4, eye + 3, z + 6));
            command(mc, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 4, eye - 2, z + 9, x + 4, eye + 3, z + 11));
            fillWindow(mc, "sky:sky");
            // In front of the window: stone hides part of it, glass shows it through a translucent surface.
            command(mc, String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", x + 1, eye, z + 5));
            command(mc, String.format(Locale.ROOT, "setblock %d %d %d minecraft:light_blue_stained_glass", x - 2, eye, z + 5));
            this.builtAt = t;
            log("built the room around " + this.origin + ", shader pack in use: " + shaderPackInUse());
        }
        if (this.builtAt < 0) {
            return;
        }
        switch (t - this.builtAt) {
            case 78, 138, 198, 238 -> {
                mc.gui.getChat().clearMessages(false);
                mc.getToasts().clear();
            }
            case 79 -> Capture.start(this.tag);
            case 80 -> {
                screenshot(mc, "front");
                presented(mc, "front-presented");
            }
            case 82 -> command(mc, String.format(Locale.ROOT, "tp @s %d %d %d -35 10",
                    this.origin.getX() - 4, this.origin.getY() - 1, this.origin.getZ() + 1));
            case 140 -> presented(mc, "side-presented");
            case 142 -> command(mc, "time set 14000");
            case 200 -> presented(mc, "night-presented");
            case 202 -> {
                command(mc, "time set 1000");
                fillWindow(mc, "minecraft:stone");
                command(mc, String.format(Locale.ROOT, "tp @s %.3f %.3f %.3f 0 0", this.home.x, this.home.y, this.home.z));
            }
            case 240 -> presented(mc, "without-presented");
            case 250 -> {
                this.done = true;
                log("RESULT: OK (built the room and took the screenshots)");
                mc.stop();
            }
            default -> {
            }
        }
    }

    private void fillWindow(Minecraft mc, String block) {
        int x = this.origin.getX();
        int eye = this.origin.getY();
        int z = this.origin.getZ() + 8;
        command(mc, String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", x - 3, eye - 1, z, x + 3, eye + 2, z, block));
    }

    private static boolean shaderPackInUse() {
        return ModList.get().isLoaded("iris") && IrisApi.getInstance().isShaderPackInUse();
    }

    private static void command(Minecraft mc, String command) {
        mc.player.connection.sendCommand(command);
    }

    private void screenshot(Minecraft mc, String view) {
        String name = "sky-smoke-" + this.tag + "-" + view + ".png";
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> log("screenshot " + name));
    }

    /** Saves the window's front buffer, which is what the player sees. */
    private void presented(Minecraft mc, String view) {
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();
        int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        GL11.glReadBuffer(GL11.GL_FRONT);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        GL11.glReadBuffer(readBuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = ((height - 1 - y) * width + x) * 4;
                image.setRGB(x, y, (pixels.get(i) & 0xFF) << 16 | (pixels.get(i + 1) & 0xFF) << 8 | pixels.get(i + 2) & 0xFF);
            }
        }
        String name = "sky-smoke-" + this.tag + "-" + view + ".png";
        try {
            Path dir = mc.gameDirectory.toPath().resolve("screenshots");
            Files.createDirectories(dir);
            ImageIO.write(image, "png", dir.resolve(name).toFile());
            log("screenshot " + name);
        } catch (IOException e) {
            log("could not save " + name + ": " + e);
        }
    }

    private static void deleteOldWorld() {
        Path saves = Path.of("saves");
        if (!Files.isDirectory(saves)) {
            return;
        }
        try (Stream<Path> worlds = Files.list(saves)) {
            for (Path world : worlds.filter(p -> p.getFileName().toString().startsWith(WORLD)).toList()) {
                try (Stream<Path> files = Files.walk(world)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(file);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not delete the old test world", e);
        }
    }

    private static void log(String message) {
        System.out.println(TAG + message);
        System.out.flush();
    }
}
