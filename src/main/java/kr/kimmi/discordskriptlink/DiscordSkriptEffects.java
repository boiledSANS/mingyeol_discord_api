package kr.kimmi.discordskriptlink;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.ExpressionType;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleEvent;
import ch.njol.util.Kleenean;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

/** Registers the public Skript syntax offered by DiscordSkriptLink. */
final class DiscordSkriptEffects {
    private DiscordSkriptEffects() { }

    static void register(DiscordSkriptLinkPlugin plugin) {
        Link.plugin = plugin;
        Unlink.plugin = plugin;
        SetName.plugin = plugin;
        Mute.plugin = plugin;
        RoleChange.plugin = plugin;
        CreateRole.plugin = plugin;
        DeleteRole.plugin = plugin;
        SetRoleColor.plugin = plugin;
        Deafen.plugin = plugin;
        KickVoiceChat.plugin = plugin;
        CreateVoiceRoom.plugin = plugin;
        CreateTextChannel.plugin = plugin;
        TransferVoiceRoom.plugin = plugin;
        RemoveVoiceChat.plugin = plugin;
        SendDiscordMessage.plugin = plugin;
        Skript.registerEffect(Link.class,
                "discord_link %player%",
                "discord_link %player% with %string%");
        Skript.registerEffect(Unlink.class, "discord_unlink %player%");
        Skript.registerEffect(SetName.class,
                "discord_setname %player% to %string%",
                "discord set name of %player% to %string%");
        Skript.registerEffect(Mute.class,
                "discord_mute %player%",
                "discord_unmute %player%");
        Skript.registerEffect(RoleChange.class,
                "add_role %player% %string%",
                "discord_add_role %player% %string%",
                "give_role %player% %string%",
                "discord_give_role %player% %string%",
                "remove_role %player% %string%",
                "discord_remove_role %player% %string%",
                "take_role %player% %string%",
                "discord_take_role %player% %string%");
        Skript.registerEffect(CreateRole.class,
                "create_role %string%",
                "discord_create_role %string%");
        Skript.registerEffect(DeleteRole.class, "delete_role %string%", "discord_delete_role %string%");
        Skript.registerEffect(SetRoleColor.class, "set_role_color %string% %string%", "discord_set_role_color %string% %string%");
        Skript.registerEffect(Deafen.class, "discord_deafen %player%", "discord_undeafen %player%");
        Skript.registerEffect(KickVoiceChat.class, "kick_voice_chat %player%", "discord_kick_voice_chat %player%");
        Skript.registerEffect(CreateVoiceRoom.class, "add_voice_room %string% %string%");
        Skript.registerEffect(CreateTextChannel.class, "create_text_channel %string% %string%", "discord_create_text_channel %string% %string%");
        Skript.registerEffect(TransferVoiceRoom.class, "transfer_voice_room %player% %string%");
        Skript.registerEffect(RemoveVoiceChat.class,
                "remove_voice_chat %string%",
                "remove_voice_room %string%");
        Skript.registerEffect(SendDiscordMessage.class, "send_discord_message %string% %string%");
        Skript.registerEvent("Discord Voice Room Created", SimpleEvent.class, DiscordVoiceRoomCreatedEvent.class,
                "[discord] voice room created");
        Skript.registerExpression(CreatedVoiceRoomId.class, String.class, ExpressionType.SIMPLE,
                "[discord] created voice room id");
        Skript.registerExpression(CreatedVoiceRoomName.class, String.class, ExpressionType.SIMPLE,
                "[discord] created voice room name");
        Skript.registerExpression(CreatedVoiceRoomCategoryId.class, String.class, ExpressionType.SIMPLE,
                "[discord] created voice room category id");
        Skript.registerEvent("Discord Role Created", SimpleEvent.class, DiscordRoleCreatedEvent.class,
                "[discord] role created");
        Skript.registerExpression(CreatedRoleId.class, String.class, ExpressionType.SIMPLE,
                "[discord] created role id");
        Skript.registerExpression(CreatedRoleName.class, String.class, ExpressionType.SIMPLE,
                "[discord] created role name");
        Skript.registerEvent("Discord Text Channel Created", SimpleEvent.class, DiscordTextChannelCreatedEvent.class,
                "[discord] text channel created");
        Skript.registerExpression(CreatedTextChannelId.class, String.class, ExpressionType.SIMPLE,
                "[discord] created text channel id");
        Skript.registerExpression(CreatedTextChannelName.class, String.class, ExpressionType.SIMPLE,
                "[discord] created text channel name");
        Skript.registerExpression(CreatedTextChannelCategoryId.class, String.class, ExpressionType.SIMPLE,
                "[discord] created text channel category id");
    }

    public static final class Link extends Effect {
        private static DiscordSkriptLinkPlugin plugin;
        private Expression<Player> player;
        private @Nullable Expression<String> nickname;

        @Override
        @SuppressWarnings("unchecked")
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            player = (Expression<Player>) expressions[0];
            nickname = matchedPattern == 1 ? (Expression<String>) expressions[1] : null;
            return true;
        }

        @Override
        protected void execute(Event event) {
            Player target = player.getSingle(event);
            if (target == null) return;
            plugin.beginLink(target, nickname == null ? null : nickname.getSingle(event));
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return "discord_link";
        }
    }

    public static final class SetName extends Effect {
        private static DiscordSkriptLinkPlugin plugin;
        private Expression<Player> player;
        private Expression<String> nickname;

        @Override
        @SuppressWarnings("unchecked")
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            player = (Expression<Player>) expressions[0];
            nickname = (Expression<String>) expressions[1];
            return true;
        }

        @Override
        protected void execute(Event event) {
            Player target = player.getSingle(event);
            String value = nickname.getSingle(event);
            if (target != null && value != null) plugin.setDiscordNickname(target, value);
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return "discord_setname";
        }
    }

    public static final class Unlink extends Effect {
        private static DiscordSkriptLinkPlugin plugin;
        private Expression<Player> player;

        @Override
        @SuppressWarnings("unchecked")
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            player = (Expression<Player>) expressions[0];
            return true;
        }

        @Override
        protected void execute(Event event) {
            Player target = player.getSingle(event);
            if (target != null) plugin.unlinkDiscordAccount(target);
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return "discord_unlink";
        }
    }

    public static final class Mute extends Effect {
        private static DiscordSkriptLinkPlugin plugin;
        private Expression<Player> player;
        private boolean muted;

        @Override
        @SuppressWarnings("unchecked")
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            player = (Expression<Player>) expressions[0];
            muted = matchedPattern == 0;
            return true;
        }

        @Override
        protected void execute(Event event) {
            Player target = player.getSingle(event);
            if (target != null) plugin.setDiscordMute(target, muted);
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return muted ? "discord_mute" : "discord_unmute";
        }
    }

    public static final class RoleChange extends Effect {
        private static DiscordSkriptLinkPlugin plugin;
        private Expression<Player> player;
        private Expression<String> roleId;
        private boolean add;

        @Override
        @SuppressWarnings("unchecked")
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            player = (Expression<Player>) expressions[0];
            roleId = (Expression<String>) expressions[1];
            add = matchedPattern < 4;
            return true;
        }

        @Override
        protected void execute(Event event) {
            Player target = player.getSingle(event);
            String value = roleId.getSingle(event);
            if (target != null && value != null) plugin.changeDiscordRole(target, value, add);
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return add ? "add_role" : "remove_role";
        }
    }

    public static final class CreateVoiceRoom extends Effect {
        private static DiscordSkriptLinkPlugin plugin;
        private Expression<String> categoryId;
        private Expression<String> channelName;

        @Override
        @SuppressWarnings("unchecked")
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            categoryId = (Expression<String>) expressions[0];
            channelName = (Expression<String>) expressions[1];
            return true;
        }

        @Override
        protected void execute(Event event) {
            String category = categoryId.getSingle(event);
            String name = channelName.getSingle(event);
            if (category != null && name != null) plugin.createVoiceRoom(category, name);
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return "add_voice_room";
        }
    }

    public static final class CreateTextChannel extends Effect {
        private static DiscordSkriptLinkPlugin plugin;
        private Expression<String> categoryId;
        private Expression<String> channelName;

        @Override
        @SuppressWarnings("unchecked")
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            categoryId = (Expression<String>) expressions[0];
            channelName = (Expression<String>) expressions[1];
            return true;
        }

        @Override
        protected void execute(Event event) {
            String category = categoryId.getSingle(event);
            String name = channelName.getSingle(event);
            if (category != null && name != null) plugin.createTextChannel(category, name);
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) { return "create_text_channel"; }
    }

    public static final class CreateRole extends Effect {
        private static DiscordSkriptLinkPlugin plugin;
        private Expression<String> roleName;

        @Override
        @SuppressWarnings("unchecked")
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            roleName = (Expression<String>) expressions[0];
            return true;
        }

        @Override
        protected void execute(Event event) {
            String name = roleName.getSingle(event);
            if (name != null) plugin.createDiscordRole(name);
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return "create_role";
        }
    }

    public static final class DeleteRole extends Effect {
        private static DiscordSkriptLinkPlugin plugin;
        private Expression<String> roleId;

        @Override
        @SuppressWarnings("unchecked")
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            roleId = (Expression<String>) expressions[0];
            return true;
        }

        @Override
        protected void execute(Event event) {
            String id = roleId.getSingle(event);
            if (id != null) plugin.deleteDiscordRole(id);
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) { return "delete_role"; }
    }

    public static final class SetRoleColor extends Effect {
        private static DiscordSkriptLinkPlugin plugin;
        private Expression<String> roleId;
        private Expression<String> color;

        @Override
        @SuppressWarnings("unchecked")
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            roleId = (Expression<String>) expressions[0];
            color = (Expression<String>) expressions[1];
            return true;
        }

        @Override
        protected void execute(Event event) {
            String id = roleId.getSingle(event);
            String value = color.getSingle(event);
            if (id != null && value != null) plugin.setDiscordRoleColor(id, value);
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) { return "set_role_color"; }
    }

    public static final class Deafen extends Effect {
        private static DiscordSkriptLinkPlugin plugin;
        private Expression<Player> player;
        private boolean deafened;

        @Override
        @SuppressWarnings("unchecked")
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            player = (Expression<Player>) expressions[0];
            deafened = matchedPattern == 0;
            return true;
        }

        @Override
        protected void execute(Event event) {
            Player target = player.getSingle(event);
            if (target != null) plugin.setDiscordDeafen(target, deafened);
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return deafened ? "discord_deafen" : "discord_undeafen";
        }
    }

    public static final class KickVoiceChat extends Effect {
        private static DiscordSkriptLinkPlugin plugin;
        private Expression<Player> player;

        @Override
        @SuppressWarnings("unchecked")
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            player = (Expression<Player>) expressions[0];
            return true;
        }

        @Override
        protected void execute(Event event) {
            Player target = player.getSingle(event);
            if (target != null) plugin.kickVoiceChat(target);
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) { return "kick_voice_chat"; }
    }

    public static final class TransferVoiceRoom extends Effect {
        private static DiscordSkriptLinkPlugin plugin;
        private Expression<Player> player;
        private Expression<String> channelId;

        @Override
        @SuppressWarnings("unchecked")
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            player = (Expression<Player>) expressions[0];
            channelId = (Expression<String>) expressions[1];
            return true;
        }

        @Override
        protected void execute(Event event) {
            Player target = player.getSingle(event);
            String channel = channelId.getSingle(event);
            if (target != null && channel != null) plugin.transferVoiceRoom(target, channel);
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return "transfer_voice_room";
        }
    }

    public static final class RemoveVoiceChat extends Effect {
        private static DiscordSkriptLinkPlugin plugin;
        private Expression<String> channelId;

        @Override
        @SuppressWarnings("unchecked")
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            channelId = (Expression<String>) expressions[0];
            return true;
        }

        @Override
        protected void execute(Event event) {
            String channel = channelId.getSingle(event);
            if (channel != null) plugin.removeVoiceChat(channel);
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return "remove_voice_chat";
        }
    }

    public static final class SendDiscordMessage extends Effect {
        private static DiscordSkriptLinkPlugin plugin;
        private Expression<String> channelId;
        private Expression<String> message;

        @Override
        @SuppressWarnings("unchecked")
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            channelId = (Expression<String>) expressions[0];
            message = (Expression<String>) expressions[1];
            return true;
        }

        @Override
        protected void execute(Event event) {
            String channel = channelId.getSingle(event);
            String text = message.getSingle(event);
            if (channel != null && text != null) plugin.sendDiscordMessage(channel, text);
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) { return "send_discord_message"; }
    }

    public static final class CreatedVoiceRoomId extends ch.njol.skript.lang.util.SimpleExpression<String> {
        @Override
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            return getParser().isCurrentEvent(DiscordVoiceRoomCreatedEvent.class);
        }

        @Override
        protected String[] get(Event event) {
            if (event instanceof DiscordVoiceRoomCreatedEvent voiceRoomCreated) {
                return new String[] { voiceRoomCreated.getChannelId() };
            }
            return new String[0];
        }

        @Override
        public boolean isSingle() {
            return true;
        }

        @Override
        public Class<? extends String> getReturnType() {
            return String.class;
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return "discord created voice room id";
        }
    }

    public static final class CreatedVoiceRoomName extends ch.njol.skript.lang.util.SimpleExpression<String> {
        @Override
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            return getParser().isCurrentEvent(DiscordVoiceRoomCreatedEvent.class);
        }

        @Override
        protected String[] get(Event event) {
            if (event instanceof DiscordVoiceRoomCreatedEvent voiceRoomCreated) {
                return new String[] { voiceRoomCreated.getChannelName() };
            }
            return new String[0];
        }

        @Override
        public boolean isSingle() {
            return true;
        }

        @Override
        public Class<? extends String> getReturnType() {
            return String.class;
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return "discord created voice room name";
        }
    }

    public static final class CreatedVoiceRoomCategoryId extends ch.njol.skript.lang.util.SimpleExpression<String> {
        @Override
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            return getParser().isCurrentEvent(DiscordVoiceRoomCreatedEvent.class);
        }

        @Override
        protected String[] get(Event event) {
            if (event instanceof DiscordVoiceRoomCreatedEvent voiceRoomCreated) {
                return new String[] { voiceRoomCreated.getCategoryId() };
            }
            return new String[0];
        }

        @Override
        public boolean isSingle() {
            return true;
        }

        @Override
        public Class<? extends String> getReturnType() {
            return String.class;
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return "discord created voice room category id";
        }
    }

    public static final class CreatedRoleId extends ch.njol.skript.lang.util.SimpleExpression<String> {
        @Override
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            return getParser().isCurrentEvent(DiscordRoleCreatedEvent.class);
        }

        @Override
        protected String[] get(Event event) {
            if (event instanceof DiscordRoleCreatedEvent roleCreated) {
                return new String[] { roleCreated.getRoleId() };
            }
            return new String[0];
        }

        @Override
        public boolean isSingle() {
            return true;
        }

        @Override
        public Class<? extends String> getReturnType() {
            return String.class;
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return "discord created role id";
        }
    }

    public static final class CreatedRoleName extends ch.njol.skript.lang.util.SimpleExpression<String> {
        @Override
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            return getParser().isCurrentEvent(DiscordRoleCreatedEvent.class);
        }

        @Override
        protected String[] get(Event event) {
            if (event instanceof DiscordRoleCreatedEvent roleCreated) {
                return new String[] { roleCreated.getRoleName() };
            }
            return new String[0];
        }

        @Override
        public boolean isSingle() {
            return true;
        }

        @Override
        public Class<? extends String> getReturnType() {
            return String.class;
        }

        @Override
        public String toString(@Nullable Event event, boolean debug) {
            return "discord created role name";
        }
    }

    public static final class CreatedTextChannelId extends ch.njol.skript.lang.util.SimpleExpression<String> {
        @Override
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            return getParser().isCurrentEvent(DiscordTextChannelCreatedEvent.class);
        }

        @Override
        protected String[] get(Event event) {
            if (event instanceof DiscordTextChannelCreatedEvent textChannelCreated) {
                return new String[] { textChannelCreated.getChannelId() };
            }
            return new String[0];
        }

        @Override public boolean isSingle() { return true; }
        @Override public Class<? extends String> getReturnType() { return String.class; }
        @Override public String toString(@Nullable Event event, boolean debug) { return "discord created text channel id"; }
    }

    public static final class CreatedTextChannelName extends ch.njol.skript.lang.util.SimpleExpression<String> {
        @Override
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            return getParser().isCurrentEvent(DiscordTextChannelCreatedEvent.class);
        }

        @Override
        protected String[] get(Event event) {
            if (event instanceof DiscordTextChannelCreatedEvent textChannelCreated) {
                return new String[] { textChannelCreated.getChannelName() };
            }
            return new String[0];
        }

        @Override public boolean isSingle() { return true; }
        @Override public Class<? extends String> getReturnType() { return String.class; }
        @Override public String toString(@Nullable Event event, boolean debug) { return "discord created text channel name"; }
    }

    public static final class CreatedTextChannelCategoryId extends ch.njol.skript.lang.util.SimpleExpression<String> {
        @Override
        public boolean init(Expression<?>[] expressions, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
            return getParser().isCurrentEvent(DiscordTextChannelCreatedEvent.class);
        }

        @Override
        protected String[] get(Event event) {
            if (event instanceof DiscordTextChannelCreatedEvent textChannelCreated) {
                return new String[] { textChannelCreated.getCategoryId() };
            }
            return new String[0];
        }

        @Override public boolean isSingle() { return true; }
        @Override public Class<? extends String> getReturnType() { return String.class; }
        @Override public String toString(@Nullable Event event, boolean debug) { return "discord created text channel category id"; }
    }
}
