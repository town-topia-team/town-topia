package com.alex.test.village.names;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

/**
 * The names villagers (and Mayors) are given: a first name and a last name,
 * "Mello Ashford". Plain and old-fashioned, and mostly neither a man's nor a
 * woman's name, since a villager's face can change with its job.
 *
 * <p>No Minecraft classes here, so it can be unit tested. Which names are taken
 * comes from {@link VillagerNameBook}, which keeps every name unique in the world.
 */
public final class VillagerNames {

    /** First names. "Mello", "Arven" and "Gwyn" are asked for by name; keep them. */
    static final List<String> FIRST_NAMES = unique(
            "Mello", "Arven", "Gwyn",
            "Alder", "Arden", "Ash", "Avery", "Bailey", "Bram", "Briar", "Brook", "Cass", "Cedar",
            "Clay", "Corin", "Dale", "Darcy", "Eden", "Ellis", "Ember", "Emery", "Fen", "Finch",
            "Flint", "Frost", "Glen", "Hale", "Harper", "Hayden", "Heath", "Hollis", "Jory", "Jules",
            "Kit", "Lark", "Lee", "Linden", "Lou", "Marlo", "Merrit", "Morgan", "Moss", "Noel",
            "Oakley", "Pax", "Perry", "Quinn", "Rain", "Reed", "Remy", "Riley", "River", "Robin",
            "Rowan", "Rue", "Sage", "Sam", "Sky", "Sloane", "Sol", "Sorrel", "Tam", "Teagan",
            "Thatcher", "Tobin", "Vale", "Wren", "Wynn", "Yarrow",
            "Addison", "Adair", "Ainsley", "Alba", "Alden", "Ames", "Ansel", "Arlo", "Aspen", "Auden",
            "Aubrey", "Basil", "Baylor", "Beck", "Bell", "Birch", "Blaine", "Blair", "Blake", "Bo",
            "Bryn", "Burl", "Cade", "Callan", "Calder", "Cam", "Carey", "Carrow", "Casey", "Cato",
            "Clem", "Cleo", "Cobb", "Colby", "Corey", "Cove", "Crane", "Crispin", "Dane", "Darby",
            "Dell", "Dorian", "Drew", "Dune", "Dusty", "Eddy", "Elm", "Elwin", "Emlyn", "Ennis",
            "Evan", "Ezra", "Fallon", "Farley", "Fern", "Fenna", "Finlay", "Fitch", "Flynn", "Ford",
            "Fox", "Gale", "Garnet", "Gentry", "Gil", "Gray", "Greer", "Hadley", "Halden", "Hallie",
            "Hart", "Haven", "Hazel", "Holland", "Holt", "Ira", "Ivo", "Ivy", "Jem", "Jody",
            "Joss", "Juniper", "Keaton", "Keely", "Kellan", "Kendal", "Kerry", "Kester", "Kip", "Lane",
            "Larkin", "Laurie", "Leland", "Lennox", "Lindy", "Linus", "Locke", "Loren", "Lowen", "Lyle",
            "Mackie", "Mallory", "Marin", "Marsh", "Meadow", "Merle", "Merrin", "Milo", "Mica", "Monroe",
            "Murphy", "Nell", "Nettle", "Niall", "Nico", "Nim", "Noa", "Norrie", "Odell", "Olly",
            "Orin", "Oriel", "Otis", "Paige", "Palmer", "Parris", "Pell", "Peregrine", "Pip", "Piper",
            "Poe", "Posy", "Prentice", "Quill", "Quincy", "Rafferty", "Raleigh", "Ramsey", "Rhys", "Ridley",
            "Roan", "Rook", "Rory", "Ross", "Rumi", "Rush", "Rye", "Sable", "Saffron", "Sasha",
            "Scout", "Sennet", "Shay", "Shelby", "Sid", "Silas", "Spencer", "Stellan", "Sterling", "Story",
            "Sully", "Sutton", "Tad", "Tansy", "Tate", "Teddy", "Thane", "Thistle", "Thorne", "Tibb",
            "Tilly", "Toby", "Tove", "Tully", "Tyne", "Ulla", "Umber", "Val", "Vance", "Vesper",
            "Wade", "Wells", "West", "Whit", "Wilder", "Willow", "Wim", "Winter", "Wylie", "Yael",
            "Yew", "Yuri", "Zane", "Zephyr", "Zuri");

    /** Last names: trades, fields and places, kept short so screens have room. */
    static final List<String> LAST_NAMES = unique(
            "Ashford", "Ashdown", "Ambrose", "Appleby", "Barley", "Barrow", "Beckett", "Bellamy", "Bingley", "Birchell",
            "Blackwood", "Brackley", "Bramble", "Briarwood", "Brooke", "Bunting", "Burrow", "Butler", "Calloway", "Carver",
            "Chandler", "Clayton", "Cobbler", "Copper", "Coppice", "Crabtree", "Cressly", "Croft", "Dalton", "Dapple",
            "Dawes", "Downey", "Dunmore", "Elderby", "Elling", "Elmsley", "Fairfax", "Fairley", "Fallow", "Farrow",
            "Fenwick", "Fernsby", "Fieldman", "Fletcher", "Foxley", "Furrow", "Gable", "Garland", "Glover", "Goodwin",
            "Gorse", "Greaves", "Greenly", "Grove", "Haddon", "Hallow", "Harrow", "Hartley", "Hatcher", "Hawthorn",
            "Hayward", "Hearth", "Heathley", "Hedger", "Heron", "Highmoor", "Hillard", "Hollow", "Holloway", "Honeywell",
            "Hopper", "Ironside", "Ivers", "Kestrel", "Kettle", "Kingsley", "Lambert", "Langley", "Larkspur", "Linwood",
            "Lockwood", "Lowell", "Marlow", "Meadows", "Mercer", "Millbrook", "Miller", "Milton", "Moorcroft", "Mosley",
            "Mossop", "Netherby", "Nettleton", "Norwood", "Oakes", "Oakhart", "Orchard", "Osgood", "Paddock", "Parsley",
            "Peat", "Pebble", "Pickett", "Pond", "Potter", "Puddle", "Quarry", "Radley", "Rainer", "Redfern",
            "Reeve", "Ridgeway", "Rowley", "Rushby", "Rushford", "Saltmarsh", "Sawyer", "Sedge", "Shaw", "Sheaf",
            "Shepherd", "Sloe", "Smithy", "Southby", "Sparrow", "Spindle", "Stack", "Stone", "Stowe", "Stubbs",
            "Summers", "Tanner", "Tarrant", "Thackery", "Thatch", "Thornbury", "Thorpe", "Thrush", "Tilley", "Tinker",
            "Tolley", "Trotter", "Tumble", "Underhill", "Upton", "Vane", "Wainwright", "Warren", "Weaver", "Westbrook",
            "Wheeler", "Whitlock", "Whitmore", "Wick", "Winslow", "Withers", "Woodley", "Woolley", "Yardley", "Yates");

    /** Random tries before walking the whole list in order. */
    private static final int RANDOM_TRIES = 64;

    private VillagerNames() {}

    /**
     * A full name nobody has: {@code taken} answers for a full name, {@code firstNameUsed}
     * for a first name alone, so first names repeat only once most are in use.
     * {@code random.applyAsInt(n)} gives 0 to n - 1.
     */
    public static String pick(Predicate<String> taken, Predicate<String> firstNameUsed, IntUnaryOperator random) {
        for (int i = 0; i < RANDOM_TRIES; i++) {
            String first = FIRST_NAMES.get(random.applyAsInt(FIRST_NAMES.size()));
            if (firstNameUsed.test(first)) continue;
            String name = join(first, LAST_NAMES.get(random.applyAsInt(LAST_NAMES.size())));
            if (name != null && !taken.test(name)) return name;
        }
        int start = random.applyAsInt(FIRST_NAMES.size());
        for (int i = 0; i < FIRST_NAMES.size(); i++) {
            String name = withLastName(FIRST_NAMES.get((start + i) % FIRST_NAMES.size()), taken, random, false);
            if (name != null) return name;
        }
        return withLastName(FIRST_NAMES.get(start), taken, random);
    }

    /**
     * {@code first} with a last name nobody has. If every last name is taken for it
     * (some 180 villagers sharing one first name), a number is added: "Mello Ashford II".
     */
    public static String withLastName(String first, Predicate<String> taken, IntUnaryOperator random) {
        return withLastName(first, taken, random, true);
    }

    private static String withLastName(String first, Predicate<String> taken, IntUnaryOperator random, boolean numbered) {
        for (int i = 0; i < RANDOM_TRIES; i++) {
            String name = join(first, LAST_NAMES.get(random.applyAsInt(LAST_NAMES.size())));
            if (name != null && !taken.test(name)) return name;
        }
        for (String last : LAST_NAMES) {
            String name = join(first, last);
            if (name != null && !taken.test(name)) return name;
        }
        if (!numbered) return null;
        String base = join(first, LAST_NAMES.get(random.applyAsInt(LAST_NAMES.size())));
        if (base == null) base = first + " " + LAST_NAMES.get(0);
        for (int n = 2; ; n++) {
            String name = base + " " + numeral(n);
            if (!taken.test(name)) return name;
        }
    }

    /** True for a name with a first and last name, false for the one-word names of older saves. */
    public static boolean hasLastName(String name) {
        return name.trim().indexOf(' ') > 0;
    }

    /** The first word of a name: "Mello" from "Mello Ashford". */
    public static String firstName(String name) {
        String trimmed = name.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }

    /** How names are compared: "mello ashford" and "Mello Ashford" are the same name. */
    public static String key(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** "Wren Wren" and the like are skipped. */
    private static String join(String first, String last) {
        return first.equalsIgnoreCase(last) ? null : first + " " + last;
    }

    private static String numeral(int n) {
        String[] tens = {"", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC"};
        String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        return n < 100 ? tens[n / 10] + ones[n % 10] : Integer.toString(n);
    }

    private static List<String> unique(String... names) {
        return Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(List.of(names))));
    }
}
