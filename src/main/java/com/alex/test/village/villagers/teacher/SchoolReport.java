package com.alex.test.village.villagers.teacher;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.SchoolBuilding;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * The School card on the Mayor's buildings page: who teaches here, how many
 * children, desks and places, what is on now and when the next bell rings.
 * Rows are "label\tvalue" so they travel as plain strings.
 */
public final class SchoolReport {

    private SchoolReport() {}

    public static List<String> rows(ServerLevel level, VillageManager village, SchoolBuilding school) {
        List<String> rows = new ArrayList<>();
        List<String> teachers = new ArrayList<>();
        int enrolled = 0;
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            Profession profession = model.brain().profession();
            if (profession == Profession.TEACHER) {
                // A teacher far away still teaches here: its lectern as last seen.
                SchoolClasses.ClassKey key = SchoolClasses.claimOf(village, model);
                if (key != null && key.school().equals(school.getItemFrameId())) {
                    teachers.add(VillageManager.displayName(model) + (npc == null ? " (unloaded)"
                            : " (Lv " + model.brain().teacherSkillLevel() + ")"));
                }
            } else if (npc != null && profession == Profession.CHILD
                    && school.getItemFrameId().equals(npc.getBrainData().hasUUID(SchoolClasses.TAG_PUPIL_SCHOOL)
                    ? npc.getBrainData().getUUID(SchoolClasses.TAG_PUPIL_SCHOOL) : null)) {
                enrolled++;
            }
        }
        rows.add("Teachers\t" + (teachers.isEmpty() ? "None" : String.join(", ", teachers)));

        int inClass = 0;
        String now = "Closed";
        for (SchoolClasses.ClassKey key : SchoolClasses.openClasses(level)) {
            if (!key.school().equals(school.getItemFrameId())) continue;
            inClass += SchoolClasses.pupilsIn(level, key).size();
            Profession subject = SchoolClasses.subject(level, key);
            now = switch (SchoolClasses.mode(level, key)) {
                case RECESS -> "Recess";
                case TRIP -> "Field trip" + (subject != null ? ": " + SchoolSubjects.lessonName(subject) : "");
                case CLASS -> subject != null ? "Lesson: " + SchoolSubjects.lessonName(subject) : "In class";
            };
        }
        rows.add("Children\t" + inClass + " in class, " + enrolled + " enrolled");

        int desks = 0, places = 0;
        List<BlockPos> lecterns = school.getLecterns();
        if (village.getGraph() != null) {
            for (BlockPos lectern : lecterns) {
                List<BlockPos> others = new ArrayList<>(lecterns);
                others.remove(lectern);
                for (SchoolSeats.Seat seat : SchoolSeats.layout(level, village.getGraph(), school, lectern, others).seats()) {
                    if (seat.overflow()) continue;
                    if (seat.chair() != null) desks++;
                    else places++;
                }
            }
        }
        rows.add("Classes\t" + lecterns.size() + " (" + desks + " desks, " + places + " places)");
        rows.add("Now\t" + now);
        rows.add("Next bell\t" + SchoolDay.nextBell(level.getDayTime(), true));
        return rows;
    }
}
