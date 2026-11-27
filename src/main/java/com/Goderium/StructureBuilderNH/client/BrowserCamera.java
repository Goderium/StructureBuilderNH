package com.Goderium.StructureBuilderNH.client;

import com.Goderium.StructureBuilderNH.util.Bounds;
import net.minecraftforge.common.util.ForgeDirection;

/** Orthographic camera. Camera changes never invalidate model geometry. */
final class BrowserCamera {
    // NEI's reference model faces south and is viewed from the southeast.
    private static final double NEI_SOUTH_YAW = -40, DEFAULT_PITCH = 20;
    private static final double DEFAULT_YAW = NEI_SOUTH_YAW + 180;
    double x, y, z, radius = 8, zoom = 1, yaw = DEFAULT_YAW, pitch = DEFAULT_PITCH;
    double fitHalfWidth = 8, fitHalfHeight = 8;
    int width = 1, height = 1;

    void reset(Bounds bounds, ForgeDirection front) {
        boolean horizontal = front.offsetX != 0 || front.offsetZ != 0;
        reset(bounds, horizontal ? NEI_SOUTH_YAW + frontYaw(front) : DEFAULT_YAW, DEFAULT_PITCH);
    }

    void resetSlice(Bounds bounds, int axis, ForgeDirection front) {
        // Looking down from +Y, the controller's horizontal front should point to the bottom of the map.
        double sliceYaw = axis == 1 ? frontYaw(front) : axis == 0 ? 90 : 0;
        reset(bounds, sliceYaw, axis == 1 ? 90 : 0);
    }

    private static double frontYaw(ForgeDirection front) {
        return Math.toDegrees(Math.atan2(-front.offsetX, front.offsetZ));
    }

    private void reset(Bounds bounds, double yaw, double pitch) {
        if (bounds == null) return;
        x = (bounds.minX + (double) bounds.maxX + 1) * .5;
        y = (bounds.minY + (double) bounds.maxY + 1) * .5;
        z = (bounds.minZ + (double) bounds.maxZ + 1) * .5;
        radius = Math.max(2, Math.sqrt(square(bounds.maxX - (double) bounds.minX + 1)
                + square(bounds.maxY - (double) bounds.minY + 1)
                + square(bounds.maxZ - (double) bounds.minZ + 1)) * .56);
        zoom = 1;
        this.yaw = yaw;
        this.pitch = pitch;
        fit(bounds);
    }

    /** Freeze the projected framing at reset time; subsequent rotation retains the user's scale. */
    private void fit(Bounds bounds) {
        double dx = bounds.maxX - (double) bounds.minX + 1, dy = bounds.maxY - (double) bounds.minY + 1;
        double dz = bounds.maxZ - (double) bounds.minZ + 1;
        double[] right = right(), up = up();
        fitHalfWidth = Math.max(.5, (Math.abs(right[0]) * dx + Math.abs(right[1]) * dy + Math.abs(right[2]) * dz) * .5);
        fitHalfHeight = Math.max(.5, (Math.abs(up[0]) * dx + Math.abs(up[1]) * dy + Math.abs(up[2]) * dz) * .5);
    }

    void rotate(double dx, double dy) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) return;
        yaw = Math.IEEEremainder(yaw + dx * .55, 360);
        pitch = Math.max(-89, Math.min(89, pitch + dy * .55));
    }

    void zoom(double steps) {
        if (Double.isFinite(steps)) zoom = Math.max(.08, Math.min(256, zoom * Math.pow(1.13, steps)));
    }

    double halfHeight() { return Math.max(fitHalfHeight, fitHalfWidth * height / width) * 1.1 / zoom; }
    double halfWidth() { return halfHeight() * width / height; }
    double far() { return Math.max(512, radius * 8); }

    void pan(double dx, double dy) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) return;
        double[] right = right(), up = up();
        double factor = halfHeight() * 2 / height;
        x += (-right[0] * dx + up[0] * dy) * factor;
        y += (-right[1] * dx + up[1] * dy) * factor;
        z += (-right[2] * dx + up[2] * dy) * factor;
    }

    double[] right() {
        double a = Math.toRadians(yaw);
        return new double[] {Math.cos(a), 0, Math.sin(a)};
    }

    double[] up() {
        double a = Math.toRadians(yaw), b = Math.toRadians(pitch);
        // Row 1 of Rx(pitch)*Ry(yaw), i.e. inverse-transform of screen-up.
        return new double[] {Math.sin(a) * Math.sin(b), Math.cos(b), -Math.cos(a) * Math.sin(b)};
    }

    double[] eye() {
        double a = Math.toRadians(yaw), b = Math.toRadians(pitch);
        return new double[] {-Math.sin(a) * Math.cos(b), Math.sin(b), Math.cos(a) * Math.cos(b)};
    }

    /** Origin and normalized direction in controller-relative coordinates. */
    double[] ray(double px, double py) {
        double u = (px / width * 2 - 1) * halfWidth();
        double v = (1 - py / height * 2) * halfHeight();
        double[] right = right(), up = up(), eye = eye();
        double distance = far() * .5;
        return new double[] { x + right[0] * u + up[0] * v + eye[0] * distance,
                y + right[1] * u + up[1] * v + eye[1] * distance,
                z + right[2] * u + up[2] * v + eye[2] * distance, -eye[0], -eye[1], -eye[2] };
    }

    private static double square(double value) { return value * value; }
}
