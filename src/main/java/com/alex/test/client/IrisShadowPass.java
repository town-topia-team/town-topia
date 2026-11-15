package com.alex.test.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Whether Iris is drawing its shadow pass right now: with a shader pack on, every
 * mob is drawn once from the sun for shadows and again from the camera. Anything
 * written to a shared buffer (the outline buffer) during the sun's pass lands in
 * the wrong spot on screen.
 *
 * <p>Looked up by name, so the mod runs without Iris. If Iris is missing or the
 * lookup fails, this always says no, which is how things were before.
 */
final class IrisShadowPass {

    private static final MethodHandle IS_SHADOW_PASS = find("isRenderingShadowPass");
    private static final MethodHandle PACK_IN_USE = find("isShaderPackInUse");
    private static Object api;

    private IrisShadowPass() {}

    static boolean active() {
        if (IS_SHADOW_PASS == null) return false;
        try {
            return (boolean) IS_SHADOW_PASS.invoke(api);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Whether a shader pack is on (Iris draws and tags things its own way then). */
    static boolean packInUse() {
        if (PACK_IN_USE == null) return false;
        try {
            return (boolean) PACK_IN_USE.invoke(api);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static MethodHandle find(String name) {
        try {
            Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            api = type.getMethod("getInstance").invoke(null);
            return MethodHandles.publicLookup().findVirtual(type, name,
                    MethodType.methodType(boolean.class));
        } catch (Throwable notThere) {
            return null;
        }
    }
}
