package net.sculklink.fabric;

import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Reads an advancement's display info on every Minecraft version this jar runs on.
 *
 * <p>26.3 turned {@link DisplayInfo} into a record, renaming each accessor:
 * {@code shouldAnnounceChat} became {@code announceToChat}, {@code getTitle} became {@code title}
 * and {@code getType} became {@code type}. A call compiled against either set throws
 * {@link NoSuchMethodError} on the other — on the server thread, which took a 26.3 server down
 * the first time a player earned an advancement. So the methods are looked up by name once, newest
 * name first, and a version where none matches simply posts no advancements.
 */
final class AdvancementDisplay {
    static final MethodHandle ANNOUNCE = find(boolean.class, "announceToChat", "shouldAnnounceChat");
    static final MethodHandle TITLE = find(Component.class, "title", "getTitle");
    static final MethodHandle TYPE = find(AdvancementType.class, "type", "getType");

    private AdvancementDisplay() {
    }

    /** True when every accessor was found, i.e. advancements can be relayed on this version. */
    static boolean supported() {
        return ANNOUNCE != null && TITLE != null && TYPE != null;
    }

    static boolean announceToChat(DisplayInfo display) throws Throwable {
        return (boolean) ANNOUNCE.invoke(display);
    }

    static Component title(DisplayInfo display) throws Throwable {
        return (Component) TITLE.invoke(display);
    }

    static AdvancementType type(DisplayInfo display) throws Throwable {
        return (AdvancementType) TYPE.invoke(display);
    }

    private static MethodHandle find(Class<?> returns, String... names) {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        for (String name : names) {
            try {
                return lookup.findVirtual(DisplayInfo.class, name, MethodType.methodType(returns));
            } catch (NoSuchMethodException | IllegalAccessException ignored) {
                // Not this version's name; try the next.
            }
        }
        return null;
    }
}
