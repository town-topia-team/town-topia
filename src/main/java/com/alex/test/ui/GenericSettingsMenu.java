package com.alex.test.ui;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.economy.MarketMemory;
import com.alex.test.village.economy.MarketPrices;
import com.alex.test.village.economy.MarketTuning;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.visitors.MerchantPricing;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.settings.SettingsSchema;
import com.alex.test.village.villagers.settings.SettingsText;
import com.alex.test.village.villagers.settings.SettingsSchemaRegistry;
import com.alex.test.village.villagers.tradesman.TradesmanLedger;
import com.alex.test.village.villagers.tradesman.settings.TradesmanSalePolicy;
import com.alex.test.village.visitors.MerchantCatalog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Server menu plus opening snapshot for any profession's generic settings
 * screen. Which schema to render is resolved once, at construction, via
 * {@link SettingsSchemaRegistry#forProfession}.
 *
 * <p>A schema's rows are flattened into one {@link RowLine} per on-screen
 * toggle/stepper: a MASTER_TOGGLE/BEHAVIOR_TOGGLE/NUMERIC_STEPPER/CHOICE row becomes
 * exactly one line, while a TIER_TOGGLES/ALLOW_LIST row becomes one line per
 * tier/entry. This is the same shape {@code GuardSettingsScreen} renders by
 * hand for swords/mobs/behaviors -- {@code GenericSettingsScreen} just walks
 * {@link #linesForSection} instead of three bespoke lists.
 */
public final class GenericSettingsMenu extends AbstractContainerMenu implements NpcUiMenu {

    /**
     * One Sale items card. {@code keep} is the item's own keep number
     * ({@code TradesmanSalePolicy.AUTO} when it follows the Behaviour tab), and
     * {@code stockHeld} what the storage room's Stock tab holds back of it.
     * {@code listed} rows always show (in storage, or the player set them up);
     * the rest are goods a merchant might want, shown only by a search.
     * {@code basePrice} is its normal price for a full stack, {@code livePrice}
     * today's in cents (300 = 3 emeralds, always whole, moved by recent sales), {@code soldLots} the half stacks the
     * village sold lately and {@code otherPaid} the cents the merchant paid for every other good, from
     * which the screen works out how much sells before the "Stop selling below" floor.
     */
    public record SaleItem(ResourceLocation id, int count, boolean allowed, int keep, int stockHeld,
                           boolean listed, int basePrice, int livePrice, float soldLots, float otherPaid) {
        SaleItem withChoice(boolean allowed, int keep) {
            return new SaleItem(id, count, allowed, keep, stockHeld, true, basePrice, livePrice, soldLots,
                    otherPaid);
        }
    }

    /**
     * Sale items plus the village's head count, which food keep numbers multiply
     * by, and the market tuning the server priced them with, which the screen
     * needs to work out the same answers.
     */
    private record SaleData(List<SaleItem> items, int villagers, MarketTuning tuning) {}

    /** One renderable toggle/stepper line. {@code entryId} is null for whole-row kinds. */
    public record RowLine(
            SettingsSchema.Row row, @Nullable String entryId, String label,
            boolean boolValue, int intValue) {

        /** Encodes this line's current value the way {@code GenericSettingsPayloads.Update} expects. */
        public String encodeValue() {
            return switch (row.kind()) {
                case NUMERIC_STEPPER, CHOICE -> Integer.toString(intValue);
                case TIER_TOGGLES, ALLOW_LIST -> entryId + ":" + boolValue;
                case MASTER_TOGGLE, BEHAVIOR_TOGGLE -> Boolean.toString(boolValue);
                case HEADER -> "";
            };
        }

        /** A heading line: drawn, but never clicked, sent or saved. */
        public boolean isHeader() {
            return row.kind() == SettingsSchema.RowKind.HEADER;
        }
    }

    private final int villagerId;
    @Nullable private final NpcEntity villager;
    private final SettingsSchema schema;
    private final List<List<RowLine>> sectionLines;
    private final List<SaleItem> saleItems;
    private final int villagers;
    private final MarketTuning tuning;
    private final TradesmanLedger.Snapshot ledger;

    /** Server-side: opened directly against the live villager. */
    public GenericSettingsMenu(int id, Inventory inventory, NpcEntity villager) {
        super(DynamicVillages.GENERIC_SETTINGS_MENU.get(), id);
        this.villagerId = villager.getId();
        this.villager = villager;
        this.schema = resolveSchema(villager.getProfession());
        this.sectionLines = buildLines(schema, ProfessionSettings.of(villager, schema));
        SaleData sale = saleDataFor(villager);
        this.saleItems = sale.items();
        this.villagers = sale.villagers();
        this.tuning = sale.tuning();
        this.ledger = ledgerFor(villager);
    }

    /** Client-side: reconstructed from the opening buffer, no live entity available. */
    public GenericSettingsMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        super(DynamicVillages.GENERIC_SETTINGS_MENU.get(), id);
        this.villagerId = buffer.readVarInt();
        this.villager = null;
        this.schema = resolveSchema(Profession.fromNetworkId(buffer.readVarInt()));
        this.sectionLines = readLines(schema, buffer);
        SaleData sale = readSaleData(buffer);
        this.saleItems = sale.items();
        this.villagers = sale.villagers();
        this.tuning = sale.tuning();
        this.ledger = TradesmanLedger.Snapshot.read(buffer);
    }

    public static void writeOpeningData(RegistryFriendlyByteBuf buffer, NpcEntity villager) {
        SettingsSchema schema = resolveSchema(villager.getProfession());
        buffer.writeVarInt(villager.getId());
        buffer.writeVarInt(villager.getProfession().ordinal());
        writeLines(buffer, buildLines(schema, ProfessionSettings.of(villager, schema)));
        writeSaleData(buffer, saleDataFor(villager));
        ledgerFor(villager).write(buffer);
    }

    private static TradesmanLedger.Snapshot ledgerFor(NpcEntity villager) {
        return villager.getProfession() == Profession.TRADESMAN
                ? TradesmanLedger.snapshot(villager) : TradesmanLedger.Snapshot.EMPTY;
    }

    private static SaleData saleDataFor(NpcEntity villager) {
        if (villager.getProfession() != Profession.TRADESMAN) {
            return new SaleData(new ArrayList<>(), 1, MarketPrices.tuning());
        }
        VillageManager village = TradesmanSalePolicy.villageOf(villager);
        ServerLevel level = villager.level() instanceof ServerLevel server ? server : null;
        StockReserves stock = level != null ? StockReserves.get(level.getServer()) : null;
        Map<Item, Integer> stored = TradesmanSalePolicy.storedSaleItems(villager);
        java.util.Set<Profession> jobs = village != null
                ? MerchantCatalog.professionsIn(village) : java.util.Set.of();
        MarketMemory market = level != null ? MarketMemory.get(level.getServer()) : null;
        long today = level != null ? MerchantPricing.today(level) : 0L;
        // Only goods a merchant could ever ask for. Dirt, sand and other
        // things in storage are never sold, so they get no switch.
        List<SaleItem> rows = new ArrayList<>();
        for (Item item : MerchantCatalog.everyCandidate()) {
            if (!TradesmanSalePolicy.canSell(item)) continue;
            int held = village != null && stock != null ? stock.reservedAmount(village, level, item) : 0;
            // The archers' own supplies are held back the same way (shown in "Keeps N").
            if (village != null) {
                held += com.alex.test.village.villagers.archer.ArcherSupplies.keptForArchers(village, item);
            }
            int count = stored.getOrDefault(item, 0);
            // Shown without a search when this village's own jobs could be
            // asked for it and there is some, or the player set it up.
            boolean listed = count > 0 && MerchantCatalog.mightAskFor(item, jobs)
                    || TradesmanSalePolicy.hasChoice(villager, item);
            int base = MerchantPricing.fullStackPrice(item);
            int live = village != null && level != null
                    ? MerchantPricing.liveCents(level, village, null, item, base) : base * 100;
            String marketKey = MerchantPricing.marketKey(item);
            float sold = village != null && market != null
                    ? (float) market.soldLots(village.getVillageId(), marketKey, today) : 0f;
            float otherPaid = village != null && market != null
                    ? (float) market.otherPaidCents(village.getVillageId(), marketKey, today) : 0f;
            rows.add(new SaleItem(BuiltInRegistries.ITEM.getKey(item), count,
                    TradesmanSalePolicy.isAllowed(villager, item),
                    TradesmanSalePolicy.keepAmount(villager, item), held, listed, base, live, sold, otherPaid));
        }
        // What is in storage first, then goods set up ahead, then the rest.
        rows.sort(Comparator.comparing((SaleItem row) -> row.listed() ? row.count() > 0 ? 0 : 1 : 2)
                .thenComparing(row -> row.id().toString()));
        return new SaleData(rows, village != null ? Math.max(1, village.getVillagers().size()) : 1,
                MarketPrices.tuning());
    }

    private static void writeSaleData(RegistryFriendlyByteBuf buffer, SaleData data) {
        buffer.writeVarInt(data.villagers());
        MarketTuning tuning = data.tuning();
        buffer.writeDouble(tuning.dropPerStack());
        buffer.writeDouble(tuning.risePerEmerald());
        buffer.writeDouble(tuning.maxRise());
        buffer.writeDouble(tuning.lowestShare());
        buffer.writeDouble(tuning.keepEachMorning());
        buffer.writeVarInt(data.items().size());
        for (SaleItem row : data.items()) {
            buffer.writeResourceLocation(row.id());
            buffer.writeVarInt(row.count());
            buffer.writeBoolean(row.allowed());
            buffer.writeInt(row.keep());
            buffer.writeVarInt(row.stockHeld());
            buffer.writeBoolean(row.listed());
            buffer.writeVarInt(row.basePrice());
            buffer.writeVarInt(row.livePrice());
            buffer.writeFloat(row.soldLots());
            buffer.writeFloat(row.otherPaid());
        }
    }

    private static SaleData readSaleData(RegistryFriendlyByteBuf buffer) {
        int villagers = buffer.readVarInt();
        MarketTuning tuning = new MarketTuning(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble());
        int count = buffer.readVarInt();
        List<SaleItem> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new SaleItem(buffer.readResourceLocation(), buffer.readVarInt(), buffer.readBoolean(),
                    buffer.readInt(), buffer.readVarInt(), buffer.readBoolean(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readFloat(), buffer.readFloat()));
        }
        return new SaleData(rows, villagers, tuning);
    }

    private static SettingsSchema resolveSchema(Profession profession) {
        return SettingsSchemaRegistry.forProfession(profession)
                .orElseThrow(() -> new IllegalStateException(
                        "no settings schema registered for " + profession));
    }

    // =========================================================================
    // Row-line snapshot: schema + current values -> flattened per-section lines
    // =========================================================================

    private static List<List<RowLine>> buildLines(SettingsSchema schema, ProfessionSettings settings) {
        List<List<RowLine>> sections = new ArrayList<>();
        for (SettingsSchema.Section section : schema.sections()) {
            List<RowLine> lines = new ArrayList<>();
            for (SettingsSchema.Row row : section.rows()) {
                switch (row.kind()) {
                    case HEADER -> lines.add(new RowLine(row, null, SettingsText.label(schema, row), false, 0));
                    case MASTER_TOGGLE, BEHAVIOR_TOGGLE -> lines.add(new RowLine(
                            row, null, SettingsText.label(schema, row), settings.getBool(row.settingId()), 0));
                    case NUMERIC_STEPPER, CHOICE -> lines.add(new RowLine(
                            row, null, SettingsText.label(schema, row), false, settings.getInt(row.settingId())));
                    case TIER_TOGGLES -> {
                        for (String tier : row.tierNames()) {
                            lines.add(new RowLine(row, tier, SettingsText.option(schema, row, tier),
                                    settings.isAllowed(row.settingId(), tier), 0));
                        }
                    }
                    case ALLOW_LIST -> {
                        for (SettingsSchema.AllowListEntry entry : row.allowListEntries()) {
                            lines.add(new RowLine(row, entry.id(), SettingsText.entry(schema, row, entry),
                                    settings.isAllowed(row.settingId(), entry.id()), 0));
                        }
                    }
                }
            }
            sections.add(lines);
        }
        return sections;
    }

    /** Same shape as {@link #buildLines}, but with placeholder values, for decoding the wire. */
    private static List<List<RowLine>> scaffoldLines(SettingsSchema schema) {
        List<List<RowLine>> sections = new ArrayList<>();
        for (SettingsSchema.Section section : schema.sections()) {
            List<RowLine> lines = new ArrayList<>();
            for (SettingsSchema.Row row : section.rows()) {
                switch (row.kind()) {
                    case HEADER, MASTER_TOGGLE, BEHAVIOR_TOGGLE, NUMERIC_STEPPER, CHOICE ->
                            lines.add(new RowLine(row, null, SettingsText.label(schema, row), false, 0));
                    case TIER_TOGGLES -> {
                        for (String tier : row.tierNames()) {
                            lines.add(new RowLine(row, tier, SettingsText.option(schema, row, tier), false, 0));
                        }
                    }
                    case ALLOW_LIST -> {
                        for (SettingsSchema.AllowListEntry entry : row.allowListEntries()) {
                            lines.add(new RowLine(row, entry.id(), SettingsText.entry(schema, row, entry), false, 0));
                        }
                    }
                }
            }
            sections.add(lines);
        }
        return sections;
    }

    /** A villager's settings as lines, for another screen's opening data (the Builder's desk). Server. */
    public static List<List<RowLine>> currentLines(NpcEntity villager) {
        SettingsSchema schema = resolveSchema(villager.getProfession());
        return buildLines(schema, ProfessionSettings.of(villager, schema));
    }

    /** Writes lines from {@link #currentLines}; read back with {@link #readLinesFor}. */
    public static void writeLinesTo(FriendlyByteBuf buffer, List<List<RowLine>> sections) {
        writeLines(buffer, sections);
    }

    /** Reads lines written by {@link #writeLinesTo}; the lists can be changed in place. */
    public static List<List<RowLine>> readLinesFor(Profession profession, FriendlyByteBuf buffer) {
        return readLines(resolveSchema(profession), buffer);
    }

    private static void writeLines(FriendlyByteBuf buffer, List<List<RowLine>> sections) {
        for (List<RowLine> lines : sections) {
            for (RowLine line : lines) {
                // A heading has no value, so it takes no space on the wire.
                if (line.isHeader()) continue;
                if (line.row().kind() == SettingsSchema.RowKind.NUMERIC_STEPPER
                        || line.row().kind() == SettingsSchema.RowKind.CHOICE) {
                    buffer.writeVarInt(line.intValue());
                } else {
                    buffer.writeBoolean(line.boolValue());
                }
            }
        }
    }

    private static List<List<RowLine>> readLines(SettingsSchema schema, FriendlyByteBuf buffer) {
        List<List<RowLine>> scaffold = scaffoldLines(schema);
        List<List<RowLine>> sections = new ArrayList<>(scaffold.size());
        for (List<RowLine> scaffoldLines : scaffold) {
            List<RowLine> lines = new ArrayList<>(scaffoldLines.size());
            for (RowLine placeholder : scaffoldLines) {
                if (placeholder.isHeader()) {
                    lines.add(placeholder);
                    continue;
                }
                if (placeholder.row().kind() == SettingsSchema.RowKind.NUMERIC_STEPPER
                        || placeholder.row().kind() == SettingsSchema.RowKind.CHOICE) {
                    lines.add(new RowLine(placeholder.row(), null, placeholder.label(),
                            false, buffer.readVarInt()));
                } else {
                    lines.add(new RowLine(placeholder.row(), placeholder.entryId(), placeholder.label(),
                            buffer.readBoolean(), 0));
                }
            }
            sections.add(lines);
        }
        return sections;
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public int villagerId() { return villagerId; }

    public SettingsSchema schema() { return schema; }

    public int sectionCount() { return sectionLines.size(); }

    public SettingsSchema.Section section(int sectionIndex) {
        return schema.sections().get(sectionIndex);
    }

    public List<RowLine> linesForSection(int sectionIndex) {
        return sectionLines.get(sectionIndex);
    }

    public RowLine line(int sectionIndex, int lineIndex) {
        return sectionLines.get(sectionIndex).get(lineIndex);
    }

    /** The line of the whole-row setting {@code settingId}; headings shift positions, ids do not. */
    public RowLine lineFor(String settingId) {
        for (List<RowLine> lines : sectionLines) {
            for (RowLine line : lines) {
                if (!line.isHeader() && line.entryId() == null
                        && line.row().settingId().equals(settingId)) return line;
            }
        }
        throw new IllegalArgumentException("no setting " + settingId);
    }

    public List<SaleItem> saleItems() { return saleItems; }

    /** Villagers in the tradesman's village when the screen opened (at least 1). */
    public int villagers() { return villagers; }

    /** How sales move prices, as the server had it when the screen opened. */
    public MarketTuning tuning() { return tuning; }

    /** The tradesman's sales as of opening the screen, newest first. */
    public TradesmanLedger.Snapshot ledger() { return ledger; }

    public void setSaleItemClient(ResourceLocation id, boolean allowed) {
        for (int i = 0; i < saleItems.size(); i++) {
            SaleItem old = saleItems.get(i);
            if (old.id().equals(id)) {
                saleItems.set(i, old.withChoice(allowed, old.keep()));
                return;
            }
        }
    }

    public void setSaleKeepClient(ResourceLocation id, int keep) {
        for (int i = 0; i < saleItems.size(); i++) {
            SaleItem old = saleItems.get(i);
            if (old.id().equals(id)) {
                saleItems.set(i, old.withChoice(old.allowed(), keep));
                return;
            }
        }
    }

    // =========================================================================
    // Client-side optimistic mutators
    // =========================================================================

    public void setBoolClient(int sectionIndex, int lineIndex, boolean value) {
        List<RowLine> lines = sectionLines.get(sectionIndex);
        RowLine old = lines.get(lineIndex);
        if (old.isHeader()) return;
        lines.set(lineIndex, new RowLine(old.row(), old.entryId(), old.label(), value, old.intValue()));
    }

    public void setIntClient(int sectionIndex, int lineIndex, int value) {
        List<RowLine> lines = sectionLines.get(sectionIndex);
        RowLine old = lines.get(lineIndex);
        if (old.isHeader()) return;
        int clamped = Math.max(old.row().minInt(), Math.min(old.row().maxInt(), value));
        lines.set(lineIndex, new RowLine(old.row(), old.entryId(), old.label(), old.boolValue(), clamped));
    }

    @Override
    public boolean stillValid(Player player) {
        return villager == null
                || NpcUiMenu.villagerStillThere(player, villager);
    }

    @Override
    public boolean isForNpc(NpcEntity npc) {
        return villager == npc;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
