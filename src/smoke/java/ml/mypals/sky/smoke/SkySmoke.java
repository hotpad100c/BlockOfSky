package ml.mypals.sky.smoke;

import ml.mypals.sky.client.ShadowHoles;
import ml.mypals.sky.smoke.mixin.CreateWorldScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Development check, not part of the mod. It builds a dark stone box high in the air with a pillar of sky blocks in the
 * middle, and a stone awning above the box. From inside the box (sunlight should come out of the pillar) and from its
 * roof (the awning's shadow must stay), it takes a screenshot with the shadow holes on and off, and from the roof one
 * with every block forced into "through" mode.
 * Run it with {@code ./gradlew runSmokeClient}; screenshots land in {@code run/smoke/screenshots}.
 */
public final class SkySmoke implements ClientModInitializer {
    private static final String TAG = "[sky-smoke] ";
    private static final String WORLD = "Sky Smoke";
    private static final int TIMEOUT_TICKS = 20 * 60 * 10;
    private static final int SETTLE_FRAMES = 40;

    private record Shot(String name, String view, boolean holes, boolean forceThrough) {
    }

    private static final List<Shot> SHOTS = List.of(
            new Shot("inside-on", "inside", true, false),
            new Shot("inside-off", "inside", false, false),
            new Shot("roof-on", "roof", true, false),
            new Shot("roof-off", "roof", false, false),
            new Shot("roof-forced-through", "roof", true, true));

    private int ticks;
    private int ticksInWorld;
    private boolean requestedScreen;
    private boolean created;
    private boolean built;
    private int builtAtTick;
    private boolean done;
    private BlockPos origin;
    private int shot = -1;
    private int waitUntilFrame;
    private boolean captured;
    private final List<String> failures = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        deleteOldWorld();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        if (this.done) {
            return;
        }
        this.ticks++;
        if (this.ticks == 1) {
            mc.options.pauseOnLostFocus = false;
        }
        if (this.ticks > TIMEOUT_TICKS) {
            finish(mc, "timed out after " + TIMEOUT_TICKS + " ticks (shot " + this.shot + ")");
            return;
        }
        if (mc.level != null && mc.player != null) {
            this.ticksInWorld++;
            this.showcase(mc);
            return;
        }
        if (this.created) {
            return;
        }
        if (mc.gui.screen() instanceof CreateWorldScreen screen) {
            WorldCreationUiState state = screen.getUiState();
            state.setName(WORLD);
            state.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            log("creating the world");
            this.created = true;
            ((CreateWorldScreenAccessor) screen).sky_smoke$onCreate();
            return;
        }
        if (!this.requestedScreen && mc.gui.overlay() == null && this.ticks > 40) {
            this.requestedScreen = true;
            CreateWorldScreen.openFresh(mc, null);
        }
    }

    private void showcase(Minecraft mc) {
        int t = this.ticksInWorld;
        if (t == 20) {
            command(mc, "time set 3000");
            command(mc, "weather clear");
            command(mc, "gamerule advance_time false");
            command(mc, "gamerule advance_weather false");
            command(mc, "tp @s ~ 200 ~ 0 0");
        }
        if (!this.built && t > 20 && mc.player.getY() > 190) {
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
            BlockPos feet = mc.player.blockPosition();
            this.origin = new BlockPos(feet.getX(), feet.getY() - 4, feet.getZ());
            int x = this.origin.getX();
            int y = this.origin.getY();
            int z = this.origin.getZ();
            // A closed dark stone box, floor at y and ceiling at y + 7, with a pillar of sky blocks from floor to
            // ceiling in the middle. The pillar is the only source of sky light inside. With the morning sun at an angle, sunlight comes out of the pillar's side away from the sun
            // and lights a strip of the floor; without the holes the box stays dark.
            command(mc, fill(x - 7, y, z - 7, x + 7, y + 7, z + 7, "minecraft:stone hollow"));
            command(mc, fill(x, y + 1, z, x, y + 6, z, "sky:sky"));
            // An awning over the box: its shadow falls on the ceiling over the pillar, and must stay there when the
            // camera is outside.
            command(mc, fill(x - 2, y + 11, z - 2, x + 2, y + 11, z + 2, "minecraft:stone"));
            this.built = true;
            this.builtAtTick = t;
            this.waitUntilFrame = ShadowHoles.framesPrepared + SETTLE_FRAMES * 2;
            log("built the room around " + this.origin + ", shader pack in use: " + shaderPackInUse());
            return;
        }
        if (this.built && ShadowHoles.framesPrepared == 0 && t - this.builtAtTick > 20 * 30) {
            finish(mc, "the shadow pass never reached the sky blocks (shadow mixin not applied, or no shadow pass)");
            return;
        }
        if (!this.built || ShadowHoles.framesPrepared < this.waitUntilFrame) {
            return;
        }
        if (this.shot >= 0 && !this.captured) {
            Shot s = SHOTS.get(this.shot);
            check(mc, s);
            mc.gui.hud.getChat().clearMessages(false);
            screenshot(mc, s.name());
            DepthDump.dump(mc, s.name());
            this.captured = true;
            this.waitUntilFrame = ShadowHoles.framesPrepared + 4;
            return;
        }
        this.shot++;
        this.captured = false;
        if (this.shot >= SHOTS.size()) {
            if (ShadowHoles.patchedShaders == 0) {
                this.failures.add("no shadow fragment program was patched");
            }
            finish(mc, null);
            return;
        }
        Shot s = SHOTS.get(this.shot);
        System.setProperty("sky.shadowHoles", Boolean.toString(s.holes()));
        System.setProperty("sky.shadowHoles.forceThrough", Boolean.toString(s.forceThrough()));
        int x = this.origin.getX();
        int y = this.origin.getY();
        int z = this.origin.getZ();
        if (s.view().equals("inside")) {
            // West of the pillar (the morning sun is in the east), looking at it and the floor it lights.
            command(mc, String.format(Locale.ROOT, "tp @s %.1f %.1f %.1f -130 28", x - 5.5, y + 2.5, z + 4.5));
        } else {
            command(mc, String.format(Locale.ROOT, "tp @s %.1f %.1f %.1f 135 40", x + 6.5, y + 9.0, z + 6.5));
        }
        this.waitUntilFrame = ShadowHoles.framesPrepared + SETTLE_FRAMES;
    }

    private void check(Minecraft mc, Shot s) {
        int[] quads = ShadowHoles.lastQuads;
        if (s.view().equals("inside")) {
            // The box is closed: all its sky light comes from the pillar, one less per block away from it.
            BlockPos next = this.origin.offset(-1, 1, 0);
            BlockPos far = this.origin.offset(-5, 1, -5);
            BlockPos pillar = this.origin.offset(0, 3, 0);
            int atPillar = mc.level.getBrightness(LightLayer.SKY, pillar);
            int nextTo = mc.level.getBrightness(LightLayer.SKY, next);
            int away = mc.level.getBrightness(LightLayer.SKY, far);
            log("sky light: pillar " + atPillar + ", next to it " + nextTo + ", 10 blocks away " + away
                    + "; on the server: " + serverSky(mc, pillar) + "/" + serverSky(mc, next) + "/" + serverSky(mc, far)
);
            if (atPillar != 15 || nextTo != 14 || away != 5 || serverSky(mc, far) != 5) {
                this.failures.add(s.name() + ": sky light " + atPillar + "/" + nextTo + "/" + away + ", expected 15/14/5");
            }
        }
        log("shot " + s.name() + ": quads front/through/far = " + Arrays.toString(quads)
                + ", samples = " + Arrays.toString(ShadowHoles.lastSamples) + ", frames drawing nothing so far = "
                + Arrays.toString(ShadowHoles.emptyFrames)
                + ", patched shaders = " + ShadowHoles.patchedShaders + ", frames = " + ShadowHoles.framesPrepared);
        if (!s.holes()) {
            if (quads[0] + quads[1] + quads[2] != 0) {
                this.failures.add(s.name() + ": holes off but faces drawn");
            }
            return;
        }
        boolean through = quads[1] > 0;
        boolean expectThrough = s.forceThrough() || s.view().equals("inside");
        if (through != expectThrough || quads[0] + quads[1] == 0) {
            this.failures.add(s.name() + ": expected " + (expectThrough ? "through" : "front") + " mode, got " + Arrays.toString(quads));
        }
        if (quads[2] == 0) {
            this.failures.add(s.name() + ": no face away from the sun");
        }
        for (int i = 0; i < 3; i++) {
            if (quads[i] > 0 && ShadowHoles.lastSamples[i] == 0) {
                this.failures.add(s.name() + ": set " + i + " drew no fragment into the shadow map");
            }
            if (ShadowHoles.emptyFrames[i] > 0) {
                this.failures.add(s.name() + ": set " + i + " drew nothing in " + ShadowHoles.emptyFrames[i] + " frames");
            }
        }
    }

    private static int serverSky(Minecraft mc, BlockPos pos) {
        var server = mc.getSingleplayerServer();
        return server == null ? -1 : server.overworld().getBrightness(LightLayer.SKY, pos);
    }

    private void finish(Minecraft mc, String error) {
        this.done = true;
        if (error != null) {
            this.failures.add(error);
        }
        if (this.failures.isEmpty()) {
            log("RESULT: OK");
        } else {
            log("RESULT: FAILED " + this.failures);
        }
        mc.stop();
    }

    private static String fill(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", x0, y0, z0, x1, y1, z1, block);
    }

    private static boolean shaderPackInUse() {
        return FabricLoader.getInstance().isModLoaded("iris") && IrisApi.getInstance().isShaderPackInUse();
    }

    private static void command(Minecraft mc, String command) {
        mc.player.connection.sendCommand(command);
    }

    private static void screenshot(Minecraft mc, String view) {
        String name = "sky-smoke-" + view + ".png";
        Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, message -> log("screenshot " + name));
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
