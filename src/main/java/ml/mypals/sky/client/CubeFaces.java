package ml.mypals.sky.client;

import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Vector3f;

final class CubeFaces {
    private static final float[][] CORNERS = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};

    interface Corner {
        void accept(float x, float y, float z, float u, float v);
    }

    private CubeFaces() {
    }

    static void corners(Direction face, float x, float y, float z, Corner corner) {
        Vector3f normal = face.step();
        Vector3f across = up(normal).cross(normal, new Vector3f());
        Vector3f along = normal.cross(across, new Vector3f());
        for (float[] c : CORNERS) {
            corner.accept(
                    x + 0.5F + 0.5F * (normal.x + c[0] * across.x + c[1] * along.x),
                    y + 0.5F + 0.5F * (normal.y + c[0] * across.y + c[1] * along.y),
                    z + 0.5F + 0.5F * (normal.z + c[0] * across.z + c[1] * along.z),
                    (c[0] + 1.0F) * 0.5F,
                    (1.0F - c[1]) * 0.5F);
        }
    }

    static Matrix4f lookingAt(Direction face) {
        Vector3f forward = face.step();
        Vector3f up = up(forward);
        return new Matrix4f().setLookAt(0, 0, 0, forward.x, forward.y, forward.z, up.x, up.y, up.z);
    }

    private static Vector3f up(Vector3f forward) {
        return forward.y != 0 ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);
    }
}
