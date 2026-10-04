package kr.kimmi.discordskriptlink;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired on the Paper main thread after this addon successfully creates a Discord voice room. */
public final class DiscordVoiceRoomCreatedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String channelId;
    private final String channelName;
    private final String categoryId;

    public DiscordVoiceRoomCreatedEvent(String channelId, String channelName, String categoryId) {
        this.channelId = channelId;
        this.channelName = channelName;
        this.categoryId = categoryId;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getChannelName() {
        return channelName;
    }

    public String getCategoryId() {
        return categoryId;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}

