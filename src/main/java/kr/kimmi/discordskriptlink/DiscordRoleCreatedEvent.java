package kr.kimmi.discordskriptlink;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired on the Paper main thread after this addon successfully creates a Discord role. */
public final class DiscordRoleCreatedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String roleId;
    private final String roleName;

    public DiscordRoleCreatedEvent(String roleId, String roleName) {
        this.roleId = roleId;
        this.roleName = roleName;
    }

    public String getRoleId() {
        return roleId;
    }

    public String getRoleName() {
        return roleName;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}

