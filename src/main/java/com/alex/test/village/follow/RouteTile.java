package com.alex.test.village.follow;

/**
 * One tile of a route, as the walker needs it: the map's tile plus the judged step to the next
 * tile. Built from the planner's unsmoothed tile path (round 2).
 *
 * @param x      block X
 * @param z      block Z
 * @param stand  floor height the body stands at on this tile
 * @param anchor the line must pass through this tile's middle (stairs, trapdoor tiles, doors,
 *               ladders: anything whose step was judged straight on, or that must not be cut)
 * @param toNext the step from this tile to the next one; ignored on the last tile
 */
public record RouteTile(int x, int z, double stand, boolean anchor, Step toNext) {
    public double centreX() {
        return x + 0.5;
    }

    public double centreZ() {
        return z + 0.5;
    }
}
