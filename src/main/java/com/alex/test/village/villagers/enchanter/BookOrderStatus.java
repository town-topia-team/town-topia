package com.alex.test.village.villagers.enchanter;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.LibraryBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.economy.StockOrderStatus;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.enchanter.behavior.EnchanterActivity;
import com.alex.test.village.villagers.enchanter.settings.EnchanterSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.network.BookOrderPayloads;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the Stock tab's Enchanter page shows: each order with its progress and
 * a one-line status ("Ready: take it from storage", "Needs a level 50
 * enchanter"), the finished orders, and every book that can be ordered with
 * why it cannot be right now.
 */
public final class BookOrderStatus {

    private BookOrderStatus() {}

    /**
     * The village's best enchanter and its library, which decide what can be
     * written; {@code doneToday} when every enchanter has written its books for the day.
     */
    private record Writer(@Nullable NpcEntity enchanter, int skill, int shelves, boolean table, boolean doneToday) {}

    public static BookOrderPayloads.Status build(ServerLevel level, @Nullable VillageManager village, UUID frameId) {
        if (village == null) return BookOrderPayloads.Status.empty(frameId);
        Writer writer = writer(village, level.getDayTime());
        BookOrders orders = BookOrders.get(level.getServer());
        UUID id = village.getVillageId();

        List<BookOrderPayloads.ChoiceView> choices = new ArrayList<>();
        for (EnchantBooks.Kind kind : EnchantBooks.KINDS) {
            for (int bookLevel = 1; bookLevel <= kind.maxLevel(); bookLevel++) {
                String blocked = blocked(writer, kind, bookLevel);
                choices.add(new BookOrderPayloads.ChoiceView(kind.id(), bookLevel, blocked == null ? "" : blocked));
            }
        }

        // Lapis above what the enchanter leaves for the player ("Lapis to leave for me").
        int reserve = writer.enchanter() == null ? 0 : ProfessionSettings.of(writer.enchanter(),
                EnchanterSettingsSchema.SCHEMA).getInt(EnchanterSettingsSchema.RESERVE_LAPIS);
        int freeLapis = storageCount(village, level, Items.LAPIS_LAZULI) - reserve;
        // Paper and leather only count when the enchanter may bind its own ("Paper and Book Crafting").
        boolean makeBooks = writer.enchanter() != null && ProfessionSettings.of(writer.enchanter(),
                EnchanterSettingsSchema.SCHEMA).getBool(EnchanterSettingsSchema.MAKE_BOOKS);
        boolean books = storageCount(village, level, Items.BOOK) > 0
                || (makeBooks && storageCount(village, level, Items.LEATHER) > 0
                    && (storageCount(village, level, Items.PAPER) >= 3
                        || storageCount(village, level, Items.SUGAR_CANE) >= 3));
        List<BookOrderPayloads.RowView> rows = new ArrayList<>();
        for (BookOrders.Order order : orders.orders(id)) {
            EnchantBooks.Kind kind = order.kindOf();
            if (kind == null) continue;
            String status;
            int tone;
            String blocked = blocked(writer, kind, order.level);
            int carried = order.held() - order.stored();
            if (order.once() && order.toMake() == 0) {
                if (carried > 0) {
                    status = "On the way to storage";
                    tone = StockOrderStatus.WAITING;
                } else {
                    status = "Ready: take it from storage";
                    tone = StockOrderStatus.FINE;
                }
            } else if (!order.once() && order.toMake() == 0) {
                status = "All here: another is written when you take one";
                tone = StockOrderStatus.FINE;
            } else if (blocked != null) {
                status = blocked;
                tone = StockOrderStatus.BLOCKED;
            } else if (freeLapis < EnchanterBalance.lapisFor(order.level)) {
                status = reserve > 0
                        ? "Waiting for lapis (" + reserve + " kept for you)"
                        : "Waiting for lapis in storage";
                tone = StockOrderStatus.WAITING;
            } else if (!books) {
                status = "Waiting for books (or paper and leather) in storage";
                tone = StockOrderStatus.WAITING;
            } else if (writer.doneToday()) {
                status = "Done for today, writes it tomorrow morning";
                tone = StockOrderStatus.WAITING;
            } else {
                status = "First in line for the enchanter (" + EnchanterBalance.booksPerDay(writer.skill())
                        + " books a day)";
                tone = StockOrderStatus.FINE;
            }
            rows.add(new BookOrderPayloads.RowView(order.id, order.kind, order.level, order.count(), order.once(),
                    order.made(), order.stored(), status, tone, order.orderedByName()));
        }

        List<BookOrderPayloads.DoneView> history = new ArrayList<>();
        for (BookOrders.Done done : orders.history(id)) {
            history.add(new BookOrderPayloads.DoneView(done.kind(), done.level(), done.count(), done.outcome(),
                    done.by(), (int) BookOrders.day(done.finished())));
        }

        String header = writer.enchanter() == null ? "No enchanter in the village yet"
                : "Best enchanter level " + writer.skill() + ", library with " + writer.shelves()
                + " bookshelves: books up to level "
                + EnchantBooks.roman(EnchanterBalance.bookCap(writer.skill(), writer.shelves()));
        return new BookOrderPayloads.Status(frameId, rows, history, choices, header);
    }

    /** Why the village cannot have this book written now; null when it can. */
    @Nullable
    private static String blocked(Writer writer, EnchantBooks.Kind kind, int bookLevel) {
        if (writer.enchanter() == null) return "No enchanter in the village";
        if (!writer.table()) return "The library needs an enchanting table";
        if (!ProfessionSettings.of(writer.enchanter(), EnchanterSettingsSchema.SCHEMA)
                .getBool(EnchanterSettingsSchema.WORK_ENABLED)) {
            return "The enchanter's work is switched off";
        }
        if (writer.skill() < kind.unlockLevel()) return "Learned at enchanter level " + kind.unlockLevel();
        if (bookLevel > EnchanterBalance.skillCap(writer.skill())) {
            return "Needs a level " + levelFor(bookLevel) + " enchanter";
        }
        if (bookLevel > EnchanterBalance.shelfCap(writer.shelves())) {
            return "Needs " + EnchanterBalance.shelvesFor(bookLevel) + " bookshelves in the library";
        }
        if (!ProfessionSettings.of(writer.enchanter(), EnchanterSettingsSchema.SCHEMA)
                .isAllowed(EnchanterSettingsSchema.ALLOWED_ENCHANTMENTS, kind.id())) {
            return "Switched off in the enchanter's settings";
        }
        return null;
    }

    /** The skill level that first writes {@code bookLevel} books. */
    private static int levelFor(int bookLevel) {
        return switch (bookLevel) {
            case 2 -> EnchanterBalance.SCHOLAR_LEVEL;
            case 3 -> EnchanterBalance.ADEPT_LEVEL;
            case 4 -> EnchanterBalance.ARCANIST_LEVEL;
            case 5 -> EnchanterBalance.MASTER_LEVEL;
            default -> 1;
        };
    }

    private static Writer writer(VillageManager village, long dayTime) {
        NpcEntity best = null;
        int bestSkill = 0;
        boolean doneToday = true;
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.getProfession() != Profession.ENCHANTER) continue;
            int skill = model.brain().enchanterSkillLevel();
            if (EnchanterActivity.booksLeftToday(npc, skill, dayTime) > 0) doneToday = false;
            if (best == null || skill > bestSkill) {
                best = npc;
                bestSkill = skill;
            }
        }
        int shelves = 0;
        boolean table = false;
        for (Building building : village.getBuildings()) {
            if (!(building instanceof LibraryBuilding library) || !library.isValid()) continue;
            boolean hasTable = !library.getEnchantingTables().isEmpty();
            if (hasTable && (!table || library.bookshelfCount() > shelves)) {
                table = true;
                shelves = library.bookshelfCount();
            } else if (!table) {
                shelves = Math.max(shelves, library.bookshelfCount());
            }
        }
        return new Writer(best, Math.max(1, bestSkill), shelves, table, best != null && doneToday);
    }

    private static int storageCount(VillageManager village, ServerLevel level, Item item) {
        int total = 0;
        for (Building building : village.getBuildings()) {
            if (building instanceof StorageBuilding storage && storage.isValid()) total += storage.countItem(level, item);
        }
        return total;
    }
}
