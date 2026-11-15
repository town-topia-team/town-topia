package com.alex.test.village.villagers.builder.project;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Every ordered build, saved with the world. A project is a blueprint already turned and
 * mirrored, stored by {@link BlueprintStore}, with the spot it goes. Nobody builds them
 * yet (round 5): they wait, show as outlines in the world and on the desk's Projects tab.
 */
public final class BuildProjects extends SavedData {
    private static final String DATA_NAME = "dynamicvillages_build_projects";
    private static final SavedData.Factory<BuildProjects> FACTORY =
            new SavedData.Factory<>(BuildProjects::new, BuildProjects::load, null);
    /** Projects one village may have waiting. */
    public static final int MAX_PER_VILLAGE = 8;

    /** One ordered build. {@code origin} is the lowest corner; the size is the turned blueprint's. */
    public static final class Project {
        public final UUID id;
        public final UUID villageId;
        public final String dimension;
        public final String name;
        public final String fileHash;
        public final BlockPos origin;
        public final int sizeX, sizeY, sizeZ, blocks;
        // Orders made in Creative were saved "free" until 10-25; that tag in old saves is ignored.
        @Nullable public final UUID orderedBy;
        public final long orderedAt;
        public String status = "Waiting for the Builder";
        // Building progress (round 5), saved so a restart carries on where it was.
        /** {@link #CLEAR}, {@link #SOLID} or {@link #REST}. */
        public int phase;
        /** Next cell of this phase, by the order in {@code BuildSite}. */
        public int cursor;
        /**
         * The cursor's layer, worked from its far end (two Builders, 10-25): its last cell not done yet, or
         * -1 until set. With one Builder it stays at the layer's end; cells past it in that layer are done.
         */
        public int back = -1;
        /** Blocks changed in this pass over every phase, and cells put off (retried next pass). */
        public int changedThisPass, deferred;
        /** Passes in a row that changed nothing but put cells off. */
        public int idlePasses;
        /** 0 to 100, for the Projects tab. */
        public int progress;
        /** The player paused it on the Projects tab; nobody works on it until resumed. */
        public boolean paused;
        /** Not saved: game time before which nobody works on it (waiting for blocks, a full chest...). */
        public long waitUntil;
        /**
         * Not saved: one Builder waiting on its own (short of blocks, a chest in its way), by villager, until
         * game time; the other Builder carries on (two Builders round 3, 10-25). Cleared where waitUntil is.
         */
        public final java.util.Map<UUID, Long> builderWaits = new java.util.HashMap<>();
        public static final int CLEAR = 0, SOLID = 1, REST = 2;

        public Project(UUID id, UUID villageId, String dimension, String name, String fileHash, BlockPos origin,
                       int sizeX, int sizeY, int sizeZ, int blocks, @Nullable UUID orderedBy, long orderedAt) {
            this.id = id;
            this.villageId = villageId;
            this.dimension = dimension;
            this.name = name;
            this.fileHash = fileHash;
            this.origin = origin;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.blocks = blocks;
            this.orderedBy = orderedBy;
            this.orderedAt = orderedAt;
        }

        public BlockPos max() {
            return origin.offset(sizeX - 1, sizeY - 1, sizeZ - 1);
        }

        /** The two boxes share at least one block. */
        public boolean overlaps(BlockPos min, BlockPos max) {
            BlockPos own = max();
            return min.getX() <= own.getX() && max.getX() >= origin.getX()
                    && min.getY() <= own.getY() && max.getY() >= origin.getY()
                    && min.getZ() <= own.getZ() && max.getZ() >= origin.getZ();
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("id", id);
            tag.putUUID("village", villageId);
            tag.putString("dimension", dimension);
            tag.putString("name", name);
            tag.putString("file", fileHash);
            tag.put("origin", NbtUtils.writeBlockPos(origin));
            tag.putInt("sx", sizeX);
            tag.putInt("sy", sizeY);
            tag.putInt("sz", sizeZ);
            tag.putInt("blocks", blocks);
            if (orderedBy != null) tag.putUUID("by", orderedBy);
            tag.putLong("at", orderedAt);
            tag.putString("status", status);
            tag.putInt("phase", phase);
            tag.putInt("cursor", cursor);
            tag.putInt("back", back);
            tag.putInt("changed", changedThisPass);
            tag.putInt("deferred", deferred);
            tag.putInt("idle", idlePasses);
            tag.putInt("progress", progress);
            tag.putBoolean("paused", paused);
            return tag;
        }

        @Nullable
        static Project load(CompoundTag tag) {
            if (!tag.hasUUID("id") || !tag.hasUUID("village")) return null;
            BlockPos origin = NbtUtils.readBlockPos(tag, "origin").orElse(null);
            if (origin == null) return null;
            Project project = new Project(tag.getUUID("id"), tag.getUUID("village"), tag.getString("dimension"),
                    tag.getString("name"), tag.getString("file"), origin, tag.getInt("sx"), tag.getInt("sy"),
                    tag.getInt("sz"), tag.getInt("blocks"), tag.hasUUID("by") ? tag.getUUID("by") : null,
                    tag.getLong("at"));
            if (tag.contains("status")) project.status = tag.getString("status");
            project.phase = tag.getInt("phase");
            project.cursor = tag.getInt("cursor");
            // Saves before 10-25 have none (getInt would say 0, a real cell): set at the next look.
            project.back = tag.contains("back") ? tag.getInt("back") : -1;
            project.changedThisPass = tag.getInt("changed");
            project.deferred = tag.getInt("deferred");
            project.idlePasses = tag.getInt("idle");
            project.progress = tag.getInt("progress");
            project.paused = tag.getBoolean("paused");
            return project;
        }
    }

    private final List<Project> projects = new ArrayList<>();

    public static BuildProjects get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public List<Project> all() {
        return List.copyOf(projects);
    }

    public List<Project> forVillage(UUID villageId) {
        List<Project> out = new ArrayList<>();
        for (Project project : projects) {
            if (project.villageId.equals(villageId)) out.add(project);
        }
        return out;
    }

    /** Projects in this world whose box meets this box. */
    @Nullable
    public Project overlapping(String dimension, BlockPos min, BlockPos max) {
        for (Project project : projects) {
            if (project.dimension.equals(dimension) && project.overlaps(min, max)) return project;
        }
        return null;
    }

    @Nullable
    public Project byId(UUID id) {
        for (Project project : projects) {
            if (project.id.equals(id)) return project;
        }
        return null;
    }

    /** True when another project still uses this stored file. */
    public boolean fileUsed(String fileHash) {
        for (Project project : projects) {
            if (project.fileHash.equals(fileHash)) return true;
        }
        return false;
    }

    /**
     * Moves a project one place earlier ({@code dir} -1) or later (+1) among its village's
     * projects; the Builder works on them in this order. False when it is already at the end.
     */
    public boolean move(UUID id, int dir) {
        int from = -1;
        for (int i = 0; i < projects.size(); i++) {
            if (projects.get(i).id.equals(id)) from = i;
        }
        if (from < 0) return false;
        UUID village = projects.get(from).villageId;
        for (int i = from + dir; i >= 0 && i < projects.size(); i += dir) {
            if (!projects.get(i).villageId.equals(village)) continue;
            Project other = projects.get(i);
            projects.set(i, projects.get(from));
            projects.set(from, other);
            setDirty();
            return true;
        }
        return false;
    }

    /** A project's progress changed: save it with the world. */
    public void touched() {
        setDirty();
    }

    public void add(Project project) {
        projects.add(project);
        setDirty();
    }

    @Nullable
    public Project remove(UUID id) {
        for (int i = 0; i < projects.size(); i++) {
            if (projects.get(i).id.equals(id)) {
                setDirty();
                return projects.remove(i);
            }
        }
        return null;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Project project : projects) list.add(project.save());
        tag.put("projects", list);
        return tag;
    }

    private static BuildProjects load(CompoundTag tag, HolderLookup.Provider registries) {
        BuildProjects data = new BuildProjects();
        ListTag list = tag.getList("projects", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Project project = Project.load(list.getCompound(i));
            if (project != null) data.projects.add(project);
        }
        return data;
    }
}
