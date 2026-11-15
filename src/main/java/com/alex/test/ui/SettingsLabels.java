package com.alex.test.ui;

/** Display wording shared by the hand-built settings menus. */
final class SettingsLabels {

    private SettingsLabels() {}

    /** "Wooden axe" becomes "Wooden Axe": switch names use Title Case. */
    static String titleCase(String name) {
        StringBuilder out = new StringBuilder(name.length());
        boolean startOfWord = true;
        for (char c : name.toCharArray()) {
            out.append(startOfWord ? Character.toUpperCase(c) : c);
            startOfWord = c == ' ';
        }
        return out.toString();
    }
}
