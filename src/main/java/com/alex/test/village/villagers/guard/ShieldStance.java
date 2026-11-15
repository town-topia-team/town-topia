package com.alex.test.village.villagers.guard;

/**
 * One defender's shield stance: whether the shield is raised, when it starts
 * taking hits, how much its guard meter has left, and whether it is broken
 * open or disabled. Server-side and not saved -- a defender that reloads comes
 * back with the shield down and a full meter.
 *
 * <p>Raised and "up" are different things: a shield that was just raised only
 * takes hits once the arm is up in the animation ({@link #isUp}), and the first
 * few ticks after that are the parry window ({@link #inParryWindow}).
 */
public final class ShieldStance {
    private boolean raised;
    private long readyAt;
    private long brokenUntil;
    private float meter = -1f;
    private long raisedAt = Long.MIN_VALUE / 2;
    private long lastBlockAt = Long.MIN_VALUE / 2;
    private boolean againstShots;

    /** Raised, whether or not the arm is up yet. */
    public boolean isRaised() {
        return raised;
    }

    /** Raised, arm up and not broken open: it takes hits now. */
    public boolean isUp(long now) {
        return raised && now >= readyAt && now >= brokenUntil;
    }

    /** True while an axe or an empty meter keeps the shield from being raised. */
    public boolean isBroken(long now) {
        return now < brokenUntil;
    }

    /**
     * Raises the shield. It takes hits after {@link GuardBalance#SHIELD_UP_TICKS}:
     * until then the animation still shows the arm coming up, and a block would
     * come from a shield not yet in front.
     */
    public void raise(long now) {
        if (raised) return;
        raised = true;
        raisedAt = now;
        readyAt = now + GuardBalance.SHIELD_UP_TICKS;
    }

    /**
     * Raised because a shot is coming: once up ({@link GuardBalance#SHIELD_UP_TICKS}
     * after raising, like any raise) it stops projectiles whichever way the
     * defender is turned (see {@code GuardSkillEffects.onIncomingDamage}).
     */
    public void raiseAgainstShots(long now) {
        if (!raised) {
            raised = true;
            raisedAt = now;
            readyAt = now + GuardBalance.SHIELD_UP_TICKS;
        }
        againstShots = true;
    }

    /** Whether the shield is up for an incoming or imminent shot. */
    public boolean isUpAgainstShots(long now) {
        return againstShots && isUp(now);
    }

    /**
     * Whether the shield may be lowered by choice: down already, or up for at
     * least {@link GuardBalance#SHIELD_MIN_RAISE_TICKS} so its raise animation
     * has played. A broken or lost shield still drops at once.
     */
    public boolean mayLower(long now) {
        return !raised || now - raisedAt >= GuardBalance.SHIELD_MIN_RAISE_TICKS;
    }

    /** Game time the shield was last raised. */
    public long raisedAt() {
        return raisedAt;
    }

    /** Records a hit taken on the shield, so the guard can answer it with a swing. */
    public void markBlocked(long now) {
        lastBlockAt = now;
    }

    /** Whether a hit has landed on the shield since it was last raised. */
    public boolean blockedSinceRaised() {
        return raised && lastBlockAt >= raisedAt;
    }

    /** Ticks since a hit last landed on the shield. */
    public long sinceLastBlock(long now) {
        return now - lastBlockAt;
    }

    public void lower() {
        raised = false;
        againstShots = false;
    }

    /** First ticks after the shield comes up, when a block counts as a parry. */
    public boolean inParryWindow(long now) {
        return isUp(now) && now - readyAt < GuardBalance.PARRY_WINDOW_TICKS;
    }

    /** Ticks since the shield became up, or 0 when it is not up. */
    public long heldUpFor(long now) {
        return isUp(now) ? now - readyAt : 0L;
    }

    /** The meter, filled on first use and capped at {@code max} as the level grows. */
    public float meter(float max) {
        if (meter < 0f || meter > max) meter = max;
        return meter;
    }

    /** Takes {@code amount} off the meter; true when it ran dry. */
    public boolean drain(float amount, float max) {
        meter = Math.max(0f, meter(max) - amount);
        return meter <= 0f;
    }

    /** Refills the meter a little; only while the shield is lowered. */
    public void regen(float max) {
        if (raised) return;
        meter = Math.min(max, meter(max) + GuardBalance.METER_REGEN_PER_TICK);
    }

    /** Lowers the shield and keeps it down until {@code until} (game time). */
    public void breakUntil(long until) {
        raised = false;
        againstShots = false;
        brokenUntil = Math.max(brokenUntil, until);
    }

    public void clear() {
        raised = false;
        againstShots = false;
        brokenUntil = 0L;
        meter = -1f;
    }
}
