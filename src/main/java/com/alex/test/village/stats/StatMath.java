package com.alex.test.village.stats;

final class StatMath {

    private StatMath() {}

    static float clampPercent(float value) {
        return Math.max(0f, Math.min(100f, value));
    }
}
