package com.alex.test.village.follow;

/**
 * Whether the body may pass over a column while the line is being straightened: a plain map
 * tile with its floor within {@link RouteLine#FLAT} of {@code nearStand}, nothing in the way at
 * body height. Stairs, trapdoors, doors, ladders and anything the map avoids answer false, so a
 * straightened line never crosses them sideways. The village side answers from the map (round 2).
 */
@FunctionalInterface
public interface OpenArea {
    boolean open(int x, int z, double nearStand);
}
