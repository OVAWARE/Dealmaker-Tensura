package com.github.ovaware.dealmaker.deal;

/** Dependency-free squared-distance calculation for 3D and X/Z-only contract regions. */
final class SpatialDistance {
    private SpatialDistance() {}

    static boolean within(double dx, double dy, double dz, double radius,
                          boolean useX, boolean useY, boolean useZ) {
        double squared = (useX ? dx * dx : 0.0) + (useY ? dy * dy : 0.0) + (useZ ? dz * dz : 0.0);
        return squared <= radius * radius;
    }
}
