package com.alex.test.client;

import com.alex.test.village.claims.ClaimLock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.List;

/** Forgets the locked village squares ({@link ClaimLock}) on leaving a world, so the next one starts unlocked. */
public final class ClaimLandClient {
    private ClaimLandClient() {}

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClaimLock.setClientLocked(null, List.of());
    }
}
