package com.covalscy.bleedout;

import java.util.List;

/**
 * Server-controlled settings as seen by the client. The config is COMMON, which NeoForge does not
 * sync, so the server sends its values on login (and on config reload). Client code must read
 * settings through here, never through {@link Config} directly. Falls back to the local config
 * before the first sync (e.g. on the main menu).
 */
public final class SyncedSettings {
    public record Values(int bleedoutTime, int reviveTime, int suicideTime, boolean enableSuicide,
                         boolean enableBleedoutTimer, boolean disableMovementWhileBleeding,
                         List<String> disabledInputsWhileBleeding) {
    }

    private static volatile Values remote;

    public static Values fromConfig() {
        return new Values(Config.bleedoutTime, Config.reviveTime, Config.suicideTime, Config.enableSuicide,
                Config.enableBleedoutTimer, Config.disableMovementWhileBleeding,
                Config.disabledInputsWhileBleeding == null ? List.of() : List.copyOf(Config.disabledInputsWhileBleeding));
    }

    public static void set(Values values) {
        remote = values;
    }

    public static void clear() {
        remote = null;
    }

    private static Values get() {
        Values r = remote;
        return r != null ? r : fromConfig();
    }

    public static int bleedoutTime() { return get().bleedoutTime(); }
    public static int reviveTime() { return get().reviveTime(); }
    public static int suicideTime() { return get().suicideTime(); }
    public static boolean enableSuicide() { return get().enableSuicide(); }
    public static boolean enableBleedoutTimer() { return get().enableBleedoutTimer(); }
    public static boolean disableMovementWhileBleeding() { return get().disableMovementWhileBleeding(); }
    public static List<String> disabledInputsWhileBleeding() { return get().disabledInputsWhileBleeding(); }

    private SyncedSettings() {
    }
}
