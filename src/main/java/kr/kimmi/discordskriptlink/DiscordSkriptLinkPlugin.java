package kr.kimmi.discordskriptlink;

import ch.njol.skript.variables.Variables;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Supplies Discord operations for Skript syntax registered in {@link DiscordSkriptEffects}. */
public final class DiscordSkriptLinkPlugin extends JavaPlugin implements Listener {
    private static final String USERNAME_VARIABLE = "discord_username::";
    private static final String NICKNAME_VARIABLE = "discord_nickname::";
    private static final String USER_ID_VARIABLE = "discord_user_id::";
    private static final String DISCORD_OWNER_VARIABLE = "discord_minecraft_uuid::";
    private static final String SHARED_MIGRATED_VARIABLE = "discord_shared_migrated::";
    private static final int LINK_ROLE_READY_RETRIES = 6;
    private static final long LINK_ROLE_RETRY_DELAY_TICKS = 100L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, PendingLink> pendingLinks = new ConcurrentHashMap<>();
    private final Set<String> claimingCodes = ConcurrentHashMap.newKeySet();
    private final Set<String> linkRoleChecksInFlight = ConcurrentHashMap.newKeySet();
    private final Object linkClaimLock = new Object();
    private final SecureRandom random = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private JDA jda;
    private String guildId;
    private int codeExpirySeconds;
    private URI serviceBaseUrl;
    private String serviceSharedSecret;
    private boolean migrateLocalLinks;
    private long joinSyncDelayTicks;
    private int sharedLinkRefreshSeconds;
    private String linkCompletionRoleId;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        guildId = getConfig().getString("guild-id", "").trim();
        codeExpirySeconds = Math.max(60, getConfig().getInt("link-code-expiry-seconds", 300));
        serviceSharedSecret = getConfig().getString("service-shared-secret", "").trim();
        migrateLocalLinks = getConfig().getBoolean("migrate-local-links", true);
        joinSyncDelayTicks = Math.max(1L, getConfig().getLong("join-sync-delay-ticks", 20L));
        sharedLinkRefreshSeconds = Math.max(30, getConfig().getInt("shared-link-refresh-seconds", 300));
        linkCompletionRoleId = getConfig().getString("link-completion-role-id", "").trim();
        if (!linkCompletionRoleId.isEmpty() && !linkCompletionRoleId.matches("\\d{17,20}")) {
            getLogger().warning("link-completion-role-id는 17~20자리 Discord 역할 ID여야 합니다. 연동 완료 역할 지급을 끕니다.");
            linkCompletionRoleId = "";
        }
        configureSharedService();
        DiscordSkriptEffects.register(this);
        Bukkit.getPluginManager().registerEvents(this, this);

        if (isSharedServiceReady()) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    synchronizeSharedLinkAsync(player.getUniqueId(), true, true);
                }
            }, joinSyncDelayTicks);
            long refreshTicks = sharedLinkRefreshSeconds * 20L;
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                Set<UUID> online = new HashSet<>();
                for (Player player : Bukkit.getOnlinePlayers()) online.add(player.getUniqueId());
                for (UUID uuid : online) synchronizeSharedLinkAsync(uuid, false, false);
            }, refreshTicks, refreshTicks);
        } else {
            getLogger().warning("service-base-url/service-shared-secret이 없어 Discord 연동은 이 서버의 Skript 변수에만 저장됩니다.");
        }

        String token = System.getenv("DISCORD_BOT_TOKEN");
        token = token == null ? "" : token.trim();
        if (token.isBlank() || guildId.equals("PUT_DISCORD_GUILD_ID_HERE")) {
            getLogger().severe("DISCORD_BOT_TOKEN 환경 변수와 config.yml의 guild-id를 설정해야 Discord 기능이 활성화됩니다.");
            return;
        }
        try {
            jda = JDABuilder.createDefault(token, GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_VOICE_STATES)
                    .enableCache(CacheFlag.VOICE_STATE)
                    .addEventListeners(new DiscordListener())
                    .build();
            Bukkit.getScheduler().runTaskAsynchronously(this, this::registerDiscordCommand);
        } catch (IllegalArgumentException exception) {
            getLogger().severe("Discord 봇 초기화에 실패했습니다: " + exception.getMessage());
        }
    }

    @Override
    public void onDisable() {
        pendingLinks.clear();
        claimingCodes.clear();
        linkRoleChecksInFlight.clear();
        if (jda != null) jda.shutdown();
    }

    /** Mirrors the globally stored link into this server's Skript variables after login. */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!isSharedServiceReady()) return;
        UUID minecraftUuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(this,
                () -> synchronizeSharedLinkAsync(minecraftUuid, true, true), joinSyncDelayTicks);
    }

    /** Starts the account-proof flow used by {@code discord_link}. */
    public void beginLink(Player player, String requestedNickname) {
        if (!isReady()) {
            player.sendMessage(color("&cDiscord 봇이 아직 준비되지 않았습니다. 관리자에게 설정을 확인해 달라고 하세요."));
            return;
        }
        requestedNickname = requestedNickname == null || requestedNickname.isBlank() ? null : requestedNickname.trim();
        if (requestedNickname != null && requestedNickname.length() > Member.MAX_NICKNAME_LENGTH) {
            player.sendMessage(color("&cDiscord 별명은 최대 32자입니다."));
            return;
        }
        pendingLinks.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        String code = newLinkCode();
        pendingLinks.put(code, new PendingLink(player.getUniqueId(), Instant.now().plusSeconds(codeExpirySeconds), requestedNickname));

        Component copyButton = Component.text("[복사]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.copyToClipboard(code))
                .hoverEvent(HoverEvent.showText(Component.text("클릭해서 연동 코드 복사", NamedTextColor.GRAY)));
        Component codeLine = Component.text("Discord 연동 코드 : ", NamedTextColor.GREEN)
                .append(Component.text(code, NamedTextColor.WHITE))
                .append(Component.space())
                .append(copyButton);
        Component instructionLine = Component.text("Discord에서 ", NamedTextColor.YELLOW)
                .append(Component.text("/연동확인 code:" + code, NamedTextColor.WHITE))
                .append(Component.text(" 를 실행하세요. " + codeExpirySeconds + "초 뒤 만료됩니다.", NamedTextColor.YELLOW));

        player.sendMessage(Component.text("\u00A0"));
        player.sendMessage(codeLine);
        player.sendMessage(instructionLine);
        player.sendMessage(Component.text("\u00A0"));
    }

    /** Removes this Minecraft account's Discord link and all associated Skript variables. */
    public void unlinkDiscordAccount(Player player) {
        UUID minecraftUuid = player.getUniqueId();
        if (isSharedServiceReady()) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    requestShared("DELETE", discordAccountPath(minecraftUuid), null);
                    Bukkit.getScheduler().runTask(this, () -> {
                        clearLocalLink(minecraftUuid, true);
                        Player online = Bukkit.getPlayer(minecraftUuid);
                        if (online != null) online.sendMessage(color("&aDiscord 연동을 해제했습니다."));
                    });
                } catch (Exception exception) {
                    getLogger().warning("공용 Discord 연동 해제 실패 (" + minecraftUuid + "): " + exception.getMessage());
                    sendMain(player, "&c공용 연동 서버에서 Discord 연동을 해제하지 못했습니다. 잠시 후 다시 시도하세요.");
                }
            });
            return;
        }

        Object linkedId = Variables.getVariable(USER_ID_VARIABLE + minecraftUuid, null, false);
        if (!(linkedId instanceof String discordId)) {
            player.sendMessage(color("&e연동된 Discord 계정이 없습니다."));
            return;
        }
        clearLocalLink(minecraftUuid, false);
        player.sendMessage(color("&aDiscord 연동을 해제했습니다."));
    }

    /** Changes the linked Discord server nickname used by {@code discord_setname}. */
    public void setDiscordNickname(Player target, String nickname) {
        if (nickname.length() > Member.MAX_NICKNAME_LENGTH) {
            target.sendMessage(color("&cDiscord 별명은 최대 32자입니다."));
            return;
        }
        withLinkedMember(target, member -> {
            try {
                member.modifyNickname(nickname).queue(
                        ignored -> Bukkit.getScheduler().runTask(this, () -> {
                            Variables.setVariable(NICKNAME_VARIABLE + target.getUniqueId(), nickname, null, false);
                            target.sendMessage(color("&aDiscord 서버 별명을 변경했습니다."));
                            updateSharedMetadataAsync(target.getUniqueId(), null, nickname);
                        }),
                        error -> sendMain(target, "&c별명을 변경하지 못했습니다. 봇 권한과 역할 위치를 확인하세요.")
                );
            } catch (RuntimeException exception) {
                sendMain(target, "&c별명을 변경할 수 없습니다. 봇 역할을 대상보다 위로 올리세요.");
            }
        });
    }

    /** Applies a server mute or unmute for {@code discord_mute}/{@code discord_unmute}. */
    public void setDiscordMute(Player target, boolean muted) {
        withLinkedMember(target, member -> {
            try {
                member.mute(muted).queue(
                        ignored -> sendMain(target, "&aDiscord 마이크를 " + (muted ? "차단" : "허용") + "했습니다."),
                        error -> sendMain(target, "&c마이크 상태를 변경하지 못했습니다. 대상이 음성 채널에 있는지, 봇 권한/역할 위치를 확인하세요.")
                );
            } catch (RuntimeException exception) {
                sendMain(target, "&c마이크 상태를 변경할 수 없습니다. 봇 역할을 대상보다 위로 올리세요.");
            }
        });
    }

    /** Adds or removes one Discord server role by its snowflake ID. */
    public void changeDiscordRole(Player target, String roleId, boolean add) {
        if (!isReady()) {
            target.sendMessage(color("&cDiscord 봇이 준비되지 않았습니다."));
            return;
        }
        if (!roleId.matches("\\d{17,20}")) {
            target.sendMessage(color("&cDiscord 역할 ID는 17~20자리 숫자여야 합니다."));
            return;
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            target.sendMessage(color("&c설정된 Discord 서버를 찾을 수 없습니다."));
            return;
        }
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            target.sendMessage(color("&c해당 역할 ID를 찾을 수 없습니다."));
            return;
        }
        Member self = guild.getSelfMember();
        if (!self.hasPermission(Permission.MANAGE_ROLES) || !self.canInteract(role)) {
            target.sendMessage(color("&c봇 역할이 대상 역할보다 위에 있어야 하며, 역할 관리 권한이 필요합니다."));
            return;
        }
        withLinkedMember(target, member -> {
            try {
                if (add) {
                    guild.addRoleToMember(member, role).queue(
                            ignored -> sendMain(target, "&aDiscord 역할 &f" + role.getName() + "&a을 지급했습니다."),
                            error -> sendMain(target, "&c역할을 지급하지 못했습니다. 봇 권한과 역할 순서를 확인하세요.")
                    );
                } else {
                    guild.removeRoleFromMember(member, role).queue(
                            ignored -> sendMain(target, "&aDiscord 역할 &f" + role.getName() + "&a을 제거했습니다."),
                            error -> sendMain(target, "&c역할을 제거하지 못했습니다. 봇 권한과 역할 순서를 확인하세요.")
                    );
                }
            } catch (RuntimeException exception) {
                sendMain(target, "&c역할을 변경할 수 없습니다. 봇 역할을 대상 역할보다 위로 올리세요.");
            }
        });
    }

    /** Creates a Discord server role and fires a Skript event with its ID. */
    public void createDiscordRole(String roleName) {
        if (roleName.isBlank() || roleName.length() > 100) {
            getLogger().warning("역할 생성 실패: 역할 이름은 1~100자여야 합니다.");
            return;
        }
        Guild guild = getConfiguredGuild();
        if (guild == null) return;
        if (!guild.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
            getLogger().warning("역할 생성 실패: 봇에 역할 관리 권한이 없습니다.");
            return;
        }
        try {
            guild.createRole().setName(roleName).queue(
                    role -> Bukkit.getScheduler().runTask(this, () -> {
                        Bukkit.getPluginManager().callEvent(new DiscordRoleCreatedEvent(role.getId(), role.getName()));
                        getLogger().info("Discord 역할 생성: " + role.getName() + " (" + role.getId() + ")");
                    }),
                    error -> getLogger().warning("역할을 만들지 못했습니다. 봇의 역할 관리 권한과 역할 위치를 확인하세요.")
            );
        } catch (RuntimeException exception) {
            getLogger().warning("역할을 만들 수 없습니다. 봇의 역할 관리 권한을 확인하세요.");
        }
    }

    /** Deletes a Discord server role by its snowflake ID. */
    public void deleteDiscordRole(String roleId) {
        if (!roleId.matches("\\d{17,20}")) {
            getLogger().warning("역할 삭제 실패: 역할 ID는 17~20자리 숫자여야 합니다.");
            return;
        }
        Guild guild = getConfiguredGuild();
        if (guild == null) return;
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            getLogger().warning("역할 삭제 실패: 역할 ID " + roleId + "를 찾을 수 없습니다.");
            return;
        }
        Member self = guild.getSelfMember();
        if (!self.hasPermission(Permission.MANAGE_ROLES) || !self.canInteract(role)) {
            getLogger().warning("역할 삭제 실패: 봇에 역할 관리 권한이 필요하며 봇 역할이 대상 역할보다 위에 있어야 합니다.");
            return;
        }
        String roleName = role.getName();
        try {
            role.delete().queue(
                    ignored -> getLogger().info("Discord 역할 삭제: " + roleName + " (" + roleId + ")"),
                    error -> getLogger().warning("역할을 삭제하지 못했습니다. 봇 권한과 역할 위치를 확인하세요.")
            );
        } catch (RuntimeException exception) {
            getLogger().warning("역할을 삭제할 수 없습니다. 봇 역할을 대상보다 위로 올리세요.");
        }
    }

    /** Updates a Discord server role color from a #RRGGBB color code. */
    public void setDiscordRoleColor(String roleId, String colorCode) {
        if (!roleId.matches("\\d{17,20}")) {
            getLogger().warning("역할 색상 변경 실패: 역할 ID는 17~20자리 숫자여야 합니다.");
            return;
        }
        if (!colorCode.matches("#[0-9A-Fa-f]{6}")) {
            getLogger().warning("역할 색상 변경 실패: 색상은 #RRGGBB 형식이어야 합니다. 예: #FFAA00");
            return;
        }
        Guild guild = getConfiguredGuild();
        if (guild == null) return;
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            getLogger().warning("역할 색상 변경 실패: 역할 ID " + roleId + "를 찾을 수 없습니다.");
            return;
        }
        Member self = guild.getSelfMember();
        if (!self.hasPermission(Permission.MANAGE_ROLES) || !self.canInteract(role)) {
            getLogger().warning("역할 색상 변경 실패: 봇에 역할 관리 권한이 필요하며 봇 역할이 대상 역할보다 위에 있어야 합니다.");
            return;
        }
        try {
            role.getManager().setColor(Color.decode(colorCode)).queue(
                    ignored -> getLogger().info("Discord 역할 색상 변경: " + role.getName() + " (" + colorCode + ")"),
                    error -> getLogger().warning("역할 색상을 변경하지 못했습니다. 봇 권한과 역할 위치를 확인하세요.")
            );
        } catch (RuntimeException exception) {
            getLogger().warning("역할 색상을 변경할 수 없습니다. 봇 역할을 대상보다 위로 올리세요.");
        }
    }

    /** Applies a server deafen or undeafen for a linked Discord member. */
    public void setDiscordDeafen(Player target, boolean deafened) {
        withLinkedMember(target, member -> {
            try {
                member.deafen(deafened).queue(
                        ignored -> sendMain(target, "&aDiscord 듣기를 " + (deafened ? "차단" : "허용") + "했습니다."),
                        error -> sendMain(target, "&c듣기 상태를 변경하지 못했습니다. 대상이 음성 채널에 있는지, 봇 권한/역할 위치를 확인하세요.")
                );
            } catch (RuntimeException exception) {
                sendMain(target, "&c듣기 상태를 변경할 수 없습니다. 봇 역할을 대상보다 위로 올리세요.");
            }
        });
    }

    /** Disconnects a linked Discord member from their current voice channel. */
    public void kickVoiceChat(Player target) {
        Guild guild = getConfiguredGuild(target);
        if (guild == null) return;
        withLinkedMember(target, member -> {
            if (!isInVoiceChannel(member)) {
                target.sendMessage(color("&e이 플레이어는 Discord 음성 채널에 접속해 있지 않습니다."));
                return;
            }
            try {
                guild.kickVoiceMember(member).queue(
                        ignored -> sendMain(target, "&aDiscord 음성 채널에서 내보냈습니다."),
                        error -> sendMain(target, "&c음성 채널에서 내보내지 못했습니다. 봇 권한과 역할 위치를 확인하세요.")
                );
            } catch (RuntimeException exception) {
                sendMain(target, "&c음성 채널에서 내보낼 수 없습니다. 봇 역할을 대상보다 위로 올리세요.");
            }
        });
    }

    /** Creates a Discord text channel under a category and fires a Skript event with its ID. */
    public void createTextChannel(String categoryId, String channelName) {
        if (!categoryId.matches("\\d{17,20}")) {
            getLogger().warning("텍스트 채널 생성 실패: 카테고리 ID는 17~20자리 숫자여야 합니다.");
            return;
        }
        if (channelName.isBlank() || channelName.length() > 100) {
            getLogger().warning("텍스트 채널 생성 실패: 채널 이름은 1~100자여야 합니다.");
            return;
        }
        Guild guild = getConfiguredGuild();
        if (guild == null) return;
        Category category = guild.getCategoryById(categoryId);
        if (category == null) {
            getLogger().warning("텍스트 채널 생성 실패: 카테고리 ID " + categoryId + "를 찾을 수 없습니다.");
            return;
        }
        try {
            category.createTextChannel(channelName).queue(
                    textChannel -> Bukkit.getScheduler().runTask(this, () -> {
                        Bukkit.getPluginManager().callEvent(new DiscordTextChannelCreatedEvent(
                                textChannel.getId(), textChannel.getName(), categoryId));
                        getLogger().info("Discord 텍스트 채널 생성: " + textChannel.getName() + " (" + textChannel.getId() + ")");
                    }),
                    error -> getLogger().warning("텍스트 채널을 만들지 못했습니다. 봇의 채널 관리 권한을 확인하세요.")
            );
        } catch (RuntimeException exception) {
            getLogger().warning("텍스트 채널을 만들 수 없습니다. 봇의 채널 관리 권한을 확인하세요.");
        }
    }

    /** Sends one message to a Discord text channel by its snowflake ID. */
    public void sendDiscordMessage(String channelId, String message) {
        if (!channelId.matches("\\d{17,20}")) {
            getLogger().warning("Discord 메시지 전송 실패: 채널 ID는 17~20자리 숫자여야 합니다.");
            return;
        }
        if (message.isBlank() || message.length() > 2_000) {
            getLogger().warning("Discord 메시지 전송 실패: 메시지는 1~2000자여야 합니다.");
            return;
        }
        Guild guild = getConfiguredGuild();
        if (guild == null) return;
        TextChannel textChannel = guild.getTextChannelById(channelId);
        if (textChannel == null) {
            getLogger().warning("Discord 메시지 전송 실패: 텍스트 채널 ID " + channelId + "를 찾을 수 없습니다.");
            return;
        }
        try {
            textChannel.sendMessage(message).queue(
                    ignored -> getLogger().info("Discord 메시지 전송: #" + textChannel.getName()),
                    error -> getLogger().warning("Discord 메시지를 전송하지 못했습니다. 봇의 채널 보기/메시지 전송 권한을 확인하세요.")
            );
        } catch (RuntimeException exception) {
            getLogger().warning("Discord 메시지를 전송할 수 없습니다. 봇의 채널 권한을 확인하세요.");
        }
    }

    /** Creates a Discord voice channel under a category. */
    public void createVoiceRoom(String categoryId, String channelName) {
        if (!categoryId.matches("\\d{17,20}")) {
            getLogger().warning("통화방 생성 실패: 카테고리 ID는 17~20자리 숫자여야 합니다.");
            return;
        }
        if (channelName.isBlank() || channelName.length() > 100) {
            getLogger().warning("통화방 생성 실패: 채널 이름은 1~100자여야 합니다.");
            return;
        }
        Guild guild = getConfiguredGuild();
        if (guild == null) return;
        Category category = guild.getCategoryById(categoryId);
        if (category == null) {
            getLogger().warning("통화방 생성 실패: 카테고리 ID " + categoryId + "를 찾을 수 없습니다.");
            return;
        }
        try {
            category.createVoiceChannel(channelName).queue(
                    voiceChannel -> Bukkit.getScheduler().runTask(this, () -> {
                        Bukkit.getPluginManager().callEvent(new DiscordVoiceRoomCreatedEvent(
                                voiceChannel.getId(), voiceChannel.getName(), categoryId));
                        getLogger().info("Discord 통화방 생성: " + voiceChannel.getName() + " (" + voiceChannel.getId() + ")");
                    }),
                    error -> getLogger().warning("통화방을 만들지 못했습니다. 봇의 채널 관리 권한을 확인하세요.")
            );
        } catch (RuntimeException exception) {
            getLogger().warning("통화방을 만들 수 없습니다. 봇의 채널 관리 권한을 확인하세요.");
        }
    }

    /** Moves the linked member to an existing Discord voice channel. */
    public void transferVoiceRoom(Player target, String channelId) {
        if (!channelId.matches("\\d{17,20}")) {
            target.sendMessage(color("&c음성 채널 ID는 17~20자리 숫자여야 합니다."));
            return;
        }
        Guild guild = getConfiguredGuild(target);
        if (guild == null) return;
        VoiceChannel voiceChannel = guild.getVoiceChannelById(channelId);
        if (voiceChannel == null) {
            target.sendMessage(color("&c해당 음성 채널 ID를 찾을 수 없습니다."));
            return;
        }
        withLinkedMember(target, member -> moveMemberToVoiceChannel(guild, member, voiceChannel, target,
                "&a통화방 &f" + voiceChannel.getName() + "&a으로 이동했습니다."));
    }

    /** Deletes one Discord voice channel by its snowflake ID. */
    public void removeVoiceChat(String channelId) {
        if (!channelId.matches("\\d{17,20}")) {
            getLogger().warning("통화방 삭제 실패: 음성 채널 ID는 17~20자리 숫자여야 합니다.");
            return;
        }
        Guild guild = getConfiguredGuild();
        if (guild == null) return;
        VoiceChannel voiceChannel = guild.getVoiceChannelById(channelId);
        if (voiceChannel == null) {
            getLogger().warning("통화방 삭제 실패: 음성 채널 ID " + channelId + "를 찾을 수 없습니다.");
            return;
        }
        String name = voiceChannel.getName();
        try {
            voiceChannel.delete().queue(
                    ignored -> getLogger().info("Discord 통화방 삭제: " + name + " (" + channelId + ")"),
                    error -> getLogger().warning("통화방을 삭제하지 못했습니다. 봇의 채널 관리 권한을 확인하세요.")
            );
        } catch (RuntimeException exception) {
            getLogger().warning("통화방을 삭제할 수 없습니다. 봇의 채널 관리 권한을 확인하세요.");
        }
    }

    private Guild getConfiguredGuild() {
        if (!isReady()) {
            getLogger().warning("Discord 봇이 준비되지 않아 요청을 처리하지 못했습니다.");
            return null;
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) getLogger().warning("설정된 Discord 서버를 찾을 수 없습니다.");
        return guild;
    }

    private Guild getConfiguredGuild(Player player) {
        if (!isReady()) {
            player.sendMessage(color("&cDiscord 봇이 준비되지 않았습니다."));
            return null;
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) player.sendMessage(color("&c설정된 Discord 서버를 찾을 수 없습니다."));
        return guild;
    }

    private boolean isInVoiceChannel(Member member) {
        GuildVoiceState voiceState = member.getVoiceState();
        return voiceState != null && voiceState.inAudioChannel();
    }

    private void moveMemberToVoiceChannel(Guild guild, Member member, VoiceChannel voiceChannel, Player player, String successMessage) {
        if (!isInVoiceChannel(member)) {
            sendMain(player, "&c이동하려면 Discord 음성 채널에 먼저 접속해야 합니다.");
            return;
        }
        try {
            guild.moveVoiceMember(member, voiceChannel).queue(
                    ignored -> sendMain(player, successMessage),
                    error -> sendMain(player, "&c통화방으로 이동하지 못했습니다. 봇에 멤버 이동 권한이 있는지 확인하세요.")
            );
        } catch (RuntimeException exception) {
            sendMain(player, "&c통화방으로 이동할 수 없습니다. 봇 권한과 역할 순서를 확인하세요.");
        }
    }

    private void withLinkedMember(Player player, java.util.function.Consumer<Member> action) {
        if (!isReady()) {
            player.sendMessage(color("&cDiscord 봇이 준비되지 않았습니다."));
            return;
        }
        Object value = Variables.getVariable(USER_ID_VARIABLE + player.getUniqueId(), null, false);
        if (!(value instanceof String discordUserId) || !discordUserId.matches("\\d{17,20}")) {
            player.sendMessage(color("&c이 플레이어는 Discord 계정을 연동하지 않았습니다."));
            return;
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            player.sendMessage(color("&c설정된 Discord 서버를 찾을 수 없습니다."));
            return;
        }
        guild.retrieveMemberById(discordUserId).queue(action,
                error -> sendMain(player, "&c연동된 Discord 사용자를 서버에서 찾지 못했습니다."));
    }

    private void registerDiscordCommand() {
        try {
            jda.awaitReady();
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) {
                getLogger().severe("guild-id에 해당하는 Discord 서버를 찾을 수 없습니다.");
                return;
            }
            guild.upsertCommand(Commands.slash("연동확인", "마인크래프트 연동 코드를 확인합니다.")
                    .addOption(OptionType.STRING, "code", "게임에 표시된 연동 코드", true)).queue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void completeLink(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null || !event.getGuild().getId().equals(guildId) || event.getMember() == null) return;
        String code = event.getOption("code").getAsString().toUpperCase(Locale.ROOT);
        PendingLink pending = pendingLinks.get(code);
        if (pending == null || pending.expiresAt().isBefore(Instant.now())) {
            pendingLinks.remove(code);
            event.reply("연동 코드가 없거나 만료되었습니다. 게임에서 `/연동`을 다시 실행하세요.").setEphemeral(true).queue();
            return;
        }
        Member member = event.getMember();
        if (pending.requestedNickname() != null && !member.getEffectiveName().equalsIgnoreCase(pending.requestedNickname())) {
            event.reply("게임에서 입력한 Discord 닉네임과 현재 서버 별명이 다릅니다.").setEphemeral(true).queue();
            return;
        }
        if (!isSharedServiceReady()) {
            completeLocalLink(event, code, member);
            return;
        }

        event.deferReply(true).queue(hook -> {
            synchronized (linkClaimLock) {
                PendingLink current = pendingLinks.get(code);
                if (current == null || current.expiresAt().isBefore(Instant.now())) {
                    pendingLinks.remove(code);
                    hook.editOriginal("연동 코드가 없거나 만료되었습니다. 게임에서 `/연동`을 다시 실행하세요.").queue();
                    return;
                }
                if (!claimingCodes.add(code)) {
                    hook.editOriginal("이 연동 코드는 현재 확인 중입니다.").queue();
                    return;
                }
            }

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                PendingLink current = pendingLinks.get(code);
                if (current == null) {
                    claimingCodes.remove(code);
                    hook.editOriginal("연동 코드가 이미 사용되었습니다.").queue();
                    return;
                }
                String discordId = event.getUser().getId();
                String discordUsername = event.getUser().getName();
                String originalNickname = member.getEffectiveName();
                try {
                    ObjectNode body = JSON.createObjectNode();
                    body.put("discordUserId", discordId);
                    body.put("discordUsername", discordUsername);
                    body.put("discordNickname", originalNickname);
                    JsonNode result = requestShared("PUT", discordAccountPath(current.minecraftUuid()), body);
                    SharedDiscordLink stored = parseSharedLink(result);
                    if (!stored.linked()) throw new IOException("공용 API가 linked=false를 반환했습니다.");

                    pendingLinks.remove(code, current);
                    Bukkit.getScheduler().runTask(this, () -> {
                        mirrorLocalLink(current.minecraftUuid(), stored.discordUserId(),
                                stored.discordUsername(), stored.discordNickname(), true);
                        Player player = Bukkit.getPlayer(current.minecraftUuid());
                        applyLinkCompletionActions(event.getGuild(), member, current.minecraftUuid(), originalNickname, player);
                        if (player != null) player.sendMessage(color("&aDiscord 계정 &f" + discordUsername + "&a 연동이 완료되었습니다."));
                    });
                    hook.editOriginal("마인크래프트 계정 연동이 완료되었습니다.").queue();
                } catch (SharedApiException exception) {
                    if (exception.statusCode() == 409) {
                        hook.editOriginal("이 Discord 계정은 이미 다른 마인크래프트 계정과 연동되어 있습니다.").queue();
                    } else {
                        getLogger().warning("공용 Discord 연동 저장 실패: " + exception.getMessage());
                        hook.editOriginal("공용 연동 서버에 저장하지 못했습니다. 잠시 후 같은 코드를 다시 입력하세요.").queue();
                    }
                } catch (Exception exception) {
                    getLogger().warning("공용 Discord 연동 저장 실패: " + exception.getMessage());
                    hook.editOriginal("공용 연동 서버에 저장하지 못했습니다. 잠시 후 같은 코드를 다시 입력하세요.").queue();
                } finally {
                    claimingCodes.remove(code);
                }
            });
        });
    }

    /** Legacy local-only claim path, retained when no shared service is configured. */
    private void completeLocalLink(SlashCommandInteractionEvent event, String code, Member member) {
        event.deferReply(true).queue(hook -> Bukkit.getScheduler().runTask(this, () -> {
            String discordId = event.getUser().getId();
            synchronized (linkClaimLock) {
                PendingLink current = pendingLinks.get(code);
                if (current == null || current.expiresAt().isBefore(Instant.now())) {
                    pendingLinks.remove(code);
                    hook.editOriginal("연동 코드가 없거나 만료되었습니다. 게임에서 `/연동`을 다시 실행하세요.").queue();
                    return;
                }
                Object previousOwner = Variables.getVariable(DISCORD_OWNER_VARIABLE + discordId, null, false);
                if (previousOwner instanceof String owner && !owner.equals(current.minecraftUuid().toString())) {
                    hook.editOriginal("이 Discord 계정은 이미 다른 마인크래프트 계정과 연동되어 있습니다.").queue();
                    return;
                }
                if (!pendingLinks.remove(code, current)) {
                    hook.editOriginal("연동 코드가 이미 사용되었습니다.").queue();
                    return;
                }
                UUID minecraftUuid = current.minecraftUuid();
                String originalNickname = member.getEffectiveName();
                mirrorLocalLink(minecraftUuid, discordId, event.getUser().getName(), originalNickname, false);
                Player player = Bukkit.getPlayer(minecraftUuid);
                applyLinkCompletionActions(event.getGuild(), member, minecraftUuid, originalNickname, player);
                if (player != null) player.sendMessage(color("&aDiscord 계정 &f" + event.getUser().getName() + "&a 연동이 완료되었습니다."));
            }
            hook.editOriginal("마인크래프트 계정 연동이 완료되었습니다.").queue();
        }));
    }

    /** Gives the configured link-completion role and formats the server nickname after a verified link. */
    private void applyLinkCompletionActions(Guild guild, Member member, UUID minecraftUuid, String originalNickname, Player onlinePlayer) {
        synchronizeKnownLinkCompletionRole(guild, member, minecraftUuid);

        String minecraftName = onlinePlayer == null ? Bukkit.getOfflinePlayer(minecraftUuid).getName() : onlinePlayer.getName();
        if (minecraftName == null || minecraftName.isBlank()) {
            getLogger().warning("연동 완료 별명 변경을 건너뜁니다: 마인크래프트 닉네임을 찾을 수 없습니다.");
            return;
        }
        String formattedNickname = originalNickname + " / " + minecraftName;
        if (formattedNickname.length() > Member.MAX_NICKNAME_LENGTH) {
            getLogger().warning("연동 완료 별명 변경을 건너뜁니다: '" + formattedNickname + "'이(가) Discord 별명 최대 32자를 초과합니다.");
            if (onlinePlayer != null) onlinePlayer.sendMessage(color("&eDiscord 별명이 32자를 초과해 자동 변경하지 못했습니다."));
            return;
        }
        try {
            member.modifyNickname(formattedNickname).queue(
                    ignored -> Bukkit.getScheduler().runTask(this, () -> {
                        Variables.setVariable(NICKNAME_VARIABLE + minecraftUuid, formattedNickname, null, false);
                        updateSharedMetadataAsync(minecraftUuid, null, formattedNickname);
                    }),
                    error -> getLogger().warning("연동 완료 별명을 변경하지 못했습니다. 봇의 별명 관리 권한과 역할 위치를 확인하세요.")
            );
        } catch (RuntimeException exception) {
            getLogger().warning("연동 완료 별명을 변경할 수 없습니다. 봇 역할을 대상보다 위로 올리세요.");
        }
    }

    private void synchronizeKnownLinkCompletionRole(Guild guild, Member member, UUID minecraftUuid) {
        if (linkCompletionRoleId.isEmpty()) return;
        if (!linkRoleChecksInFlight.add(member.getId())) return;
        grantLinkCompletionRoleIfMissing(guild, member, minecraftUuid);
    }

    /** Reconciles the link-completion role after a linked Minecraft account joins this server. */
    private void synchronizeLinkCompletionRoleAsync(UUID minecraftUuid, String discordUserId) {
        synchronizeLinkCompletionRoleAsync(minecraftUuid, discordUserId, LINK_ROLE_READY_RETRIES);
    }

    private void synchronizeLinkCompletionRoleAsync(UUID minecraftUuid, String discordUserId, int readyRetriesRemaining) {
        if (linkCompletionRoleId.isEmpty()) return;
        if (!isReady()) {
            if (readyRetriesRemaining > 0 && isEnabled()) {
                Bukkit.getScheduler().runTaskLater(this,
                        () -> synchronizeLinkCompletionRoleAsync(minecraftUuid, discordUserId, readyRetriesRemaining - 1),
                        LINK_ROLE_RETRY_DELAY_TICKS);
            } else {
                getLogger().warning("접속 연동 역할 동기화를 건너뜁니다: Discord 봇이 준비되지 않았습니다 (" + minecraftUuid + ").");
            }
            return;
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            getLogger().warning("접속 연동 역할 동기화 실패: 설정된 Discord 서버를 찾을 수 없습니다.");
            return;
        }
        Role completionRole = guild.getRoleById(linkCompletionRoleId);
        if (completionRole == null) {
            getLogger().warning("연동 완료 역할을 찾을 수 없습니다: " + linkCompletionRoleId);
            return;
        }
        if (completionRole.isManaged()) {
            getLogger().warning("연동 완료 역할을 지급할 수 없습니다: Discord에서 관리되는 역할입니다 (" + linkCompletionRoleId + ").");
            return;
        }
        Member self = guild.getSelfMember();
        if (!self.hasPermission(Permission.MANAGE_ROLES) || !self.canInteract(completionRole)) {
            getLogger().warning("연동 완료 역할을 지급할 수 없습니다. 봇에 역할 관리 권한이 필요하고 봇 역할이 대상 역할보다 위에 있어야 합니다.");
            return;
        }
        if (!linkRoleChecksInFlight.add(discordUserId)) return;

        try {
            guild.retrieveMemberById(discordUserId).queue(
                    member -> grantLinkCompletionRoleIfMissing(guild, member, minecraftUuid),
                    error -> {
                        linkRoleChecksInFlight.remove(discordUserId);
                        getLogger().warning("접속 연동 역할 동기화 실패: Discord 서버에서 사용자를 찾지 못했습니다 ("
                                + discordUserId + "): " + error.getMessage());
                    }
            );
        } catch (RuntimeException exception) {
            linkRoleChecksInFlight.remove(discordUserId);
            getLogger().warning("접속 연동 역할 동기화를 시작하지 못했습니다 (" + discordUserId + "): " + exception.getMessage());
        }
    }

    /** Adds the completion role only when it is absent, making join reconciliation idempotent. */
    private void grantLinkCompletionRoleIfMissing(Guild guild, Member member, UUID minecraftUuid) {
        String discordUserId = member.getId();
        if (!isEnabled()) {
            linkRoleChecksInFlight.remove(discordUserId);
            return;
        }

        try {
            Role completionRole = guild.getRoleById(linkCompletionRoleId);
            if (completionRole == null) {
                linkRoleChecksInFlight.remove(discordUserId);
                getLogger().warning("연동 완료 역할을 찾을 수 없습니다: " + linkCompletionRoleId);
                return;
            }
            if (member.getRoles().contains(completionRole)) {
                linkRoleChecksInFlight.remove(discordUserId);
                return;
            }
            if (completionRole.isManaged()) {
                linkRoleChecksInFlight.remove(discordUserId);
                getLogger().warning("연동 완료 역할을 지급할 수 없습니다: Discord에서 관리되는 역할입니다 (" + linkCompletionRoleId + ").");
                return;
            }
            Member self = guild.getSelfMember();
            if (!self.hasPermission(Permission.MANAGE_ROLES) || !self.canInteract(completionRole)) {
                linkRoleChecksInFlight.remove(discordUserId);
                getLogger().warning("연동 완료 역할을 지급할 수 없습니다. 봇에 역할 관리 권한이 필요하고 봇 역할이 대상 역할보다 위에 있어야 합니다.");
                return;
            }

            guild.addRoleToMember(member, completionRole).queue(
                    ignored -> {
                        linkRoleChecksInFlight.remove(discordUserId);
                        getLogger().info("연동 완료 역할 지급: " + discordUserId + " (Minecraft " + minecraftUuid + ")");
                    },
                    error -> {
                        linkRoleChecksInFlight.remove(discordUserId);
                        getLogger().warning("연동 완료 역할을 지급하지 못했습니다 (" + discordUserId + "): " + error.getMessage());
                    }
            );
        } catch (RuntimeException exception) {
            linkRoleChecksInFlight.remove(discordUserId);
            getLogger().warning("연동 완료 역할을 지급할 수 없습니다 (" + discordUserId + "): " + exception.getMessage());
        }
    }

    private void configureSharedService() {
        String configuredUrl = getConfig().getString("service-base-url", "").trim().replaceAll("/+$", "");
        if (configuredUrl.isBlank() || configuredUrl.startsWith("PUT_")) return;
        try {
            URI parsed = URI.create(configuredUrl);
            if (parsed.getHost() == null || !("https".equalsIgnoreCase(parsed.getScheme()) || "http".equalsIgnoreCase(parsed.getScheme()))) {
                throw new IllegalArgumentException("HTTP(S) 주소가 아닙니다.");
            }
            serviceBaseUrl = parsed;
        } catch (IllegalArgumentException exception) {
            getLogger().severe("config.yml의 service-base-url이 올바르지 않습니다: " + exception.getMessage());
        }
    }

    private boolean isSharedServiceReady() {
        return serviceBaseUrl != null
                && !serviceSharedSecret.isBlank()
                && !serviceSharedSecret.startsWith("PUT_");
    }

    private void synchronizeSharedLinkAsync(UUID minecraftUuid, boolean logFailure, boolean synchronizeCompletionRole) {
        if (!isSharedServiceReady()) return;
        LocalDiscordLink local = readLocalLink(minecraftUuid);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                SharedDiscordLink shared = parseSharedLink(requestShared("GET", discordAccountPath(minecraftUuid), null));
                if (shared.linked()) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        mirrorLocalLink(minecraftUuid, shared.discordUserId(),
                                shared.discordUsername(), shared.discordNickname(), true);
                        if (synchronizeCompletionRole) {
                            synchronizeLinkCompletionRoleAsync(minecraftUuid, shared.discordUserId());
                        }
                    });
                    return;
                }

                if (migrateLocalLinks && !local.migrated() && local.isValid()) {
                    migrateLocalLink(minecraftUuid, local, synchronizeCompletionRole);
                } else {
                    Bukkit.getScheduler().runTask(this, () -> clearLocalLink(minecraftUuid, true));
                }
            } catch (Exception exception) {
                // Network/API failures must never erase the last known local mirror.
                if (logFailure) {
                    getLogger().warning("공용 Discord 연동 정보 동기화 실패 (" + minecraftUuid + "): " + exception.getMessage());
                }
            }
        });
    }

    private void migrateLocalLink(UUID minecraftUuid, LocalDiscordLink local, boolean synchronizeCompletionRole) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("discordUserId", local.discordUserId());
        if (!local.discordUsername().isBlank()) body.put("discordUsername", local.discordUsername());
        if (!local.discordNickname().isBlank()) body.put("discordNickname", local.discordNickname());
        try {
            SharedDiscordLink stored = parseSharedLink(requestShared("PUT", discordAccountPath(minecraftUuid), body));
            if (!stored.linked()) throw new IOException("공용 API가 linked=false를 반환했습니다.");
            Bukkit.getScheduler().runTask(this, () -> {
                mirrorLocalLink(minecraftUuid, stored.discordUserId(), stored.discordUsername(), stored.discordNickname(), true);
                if (synchronizeCompletionRole) {
                    synchronizeLinkCompletionRoleAsync(minecraftUuid, stored.discordUserId());
                }
                getLogger().info("기존 Discord 연동을 공용 DB로 이전했습니다: " + minecraftUuid);
            });
        } catch (SharedApiException exception) {
            if (exception.statusCode() == 409) {
                Bukkit.getScheduler().runTask(this, () -> clearLocalLink(minecraftUuid, true));
                getLogger().warning("기존 Discord 연동 이전 충돌로 로컬 사본을 정리했습니다 (" + minecraftUuid + "): " + exception.getMessage());
                return;
            }
            throw exception;
        }
    }

    /** Keeps Skript compatibility while the authoritative link lives in the shared database. */
    private void mirrorLocalLink(UUID minecraftUuid, String discordUserId, String username, String nickname, boolean migrated) {
        if (discordUserId == null || !discordUserId.matches("\\d{17,20}")) {
            getLogger().warning("공용 API가 올바르지 않은 Discord 사용자 ID를 반환했습니다: " + discordUserId);
            return;
        }
        Object previousValue = Variables.getVariable(USER_ID_VARIABLE + minecraftUuid, null, false);
        if (previousValue instanceof String previousId && !previousId.equals(discordUserId)) {
            removeLocalOwner(previousId, minecraftUuid);
        }
        Variables.setVariable(USER_ID_VARIABLE + minecraftUuid, discordUserId, null, false);
        setOrDeleteVariable(USERNAME_VARIABLE + minecraftUuid, username);
        setOrDeleteVariable(NICKNAME_VARIABLE + minecraftUuid, nickname);
        Variables.setVariable(DISCORD_OWNER_VARIABLE + discordUserId, minecraftUuid.toString(), null, false);
        if (migrated) Variables.setVariable(SHARED_MIGRATED_VARIABLE + minecraftUuid, true, null, false);
        else Variables.deleteVariable(SHARED_MIGRATED_VARIABLE + minecraftUuid, null, false);
    }

    private void clearLocalLink(UUID minecraftUuid, boolean migrated) {
        Object linkedId = Variables.getVariable(USER_ID_VARIABLE + minecraftUuid, null, false);
        if (linkedId instanceof String discordId) removeLocalOwner(discordId, minecraftUuid);
        Variables.deleteVariable(USERNAME_VARIABLE + minecraftUuid, null, false);
        Variables.deleteVariable(NICKNAME_VARIABLE + minecraftUuid, null, false);
        Variables.deleteVariable(USER_ID_VARIABLE + minecraftUuid, null, false);
        if (migrated) Variables.setVariable(SHARED_MIGRATED_VARIABLE + minecraftUuid, true, null, false);
        else Variables.deleteVariable(SHARED_MIGRATED_VARIABLE + minecraftUuid, null, false);
    }

    private void removeLocalOwner(String discordId, UUID minecraftUuid) {
        Object owner = Variables.getVariable(DISCORD_OWNER_VARIABLE + discordId, null, false);
        if (minecraftUuid.toString().equals(owner)) {
            Variables.deleteVariable(DISCORD_OWNER_VARIABLE + discordId, null, false);
        }
    }

    private void setOrDeleteVariable(String name, String value) {
        if (value == null || value.isBlank()) Variables.deleteVariable(name, null, false);
        else Variables.setVariable(name, value, null, false);
    }

    private LocalDiscordLink readLocalLink(UUID minecraftUuid) {
        Object id = Variables.getVariable(USER_ID_VARIABLE + minecraftUuid, null, false);
        Object username = Variables.getVariable(USERNAME_VARIABLE + minecraftUuid, null, false);
        Object nickname = Variables.getVariable(NICKNAME_VARIABLE + minecraftUuid, null, false);
        Object migrated = Variables.getVariable(SHARED_MIGRATED_VARIABLE + minecraftUuid, null, false);
        return new LocalDiscordLink(
                id instanceof String value ? value : "",
                username instanceof String value ? value : "",
                nickname instanceof String value ? value : "",
                Boolean.TRUE.equals(migrated) || "true".equalsIgnoreCase(String.valueOf(migrated))
        );
    }

    private void updateSharedMetadataAsync(UUID minecraftUuid, String usernameOverride, String nicknameOverride) {
        if (!isSharedServiceReady()) return;
        LocalDiscordLink local = readLocalLink(minecraftUuid);
        if (!local.isValid()) return;
        String username = usernameOverride == null ? local.discordUsername() : usernameOverride;
        String nickname = nicknameOverride == null ? local.discordNickname() : nicknameOverride;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            ObjectNode body = JSON.createObjectNode();
            body.put("discordUserId", local.discordUserId());
            if (!username.isBlank()) body.put("discordUsername", username);
            if (!nickname.isBlank()) body.put("discordNickname", nickname);
            try {
                requestShared("PUT", discordAccountPath(minecraftUuid), body);
            } catch (Exception exception) {
                getLogger().warning("공용 Discord 표시 정보 갱신 실패 (" + minecraftUuid + "): " + exception.getMessage());
            }
        });
    }

    private JsonNode requestShared(String method, String path, JsonNode body) throws IOException, InterruptedException {
        if (!isSharedServiceReady()) throw new IOException("공용 Discord 연동 서비스가 설정되지 않았습니다.");
        HttpRequest.Builder builder = HttpRequest.newBuilder(serviceBaseUrl.resolve(path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + serviceSharedSecret)
                .header("Accept", "application/json");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode result;
        try {
            result = response.body() == null || response.body().isBlank()
                    ? JSON.createObjectNode()
                    : JSON.readTree(response.body());
        } catch (IOException exception) {
            throw new SharedApiException(response.statusCode(), "JSON이 아닌 응답을 받았습니다.");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String error = result.path("error").asText("HTTP " + response.statusCode());
            throw new SharedApiException(response.statusCode(), error);
        }
        return result;
    }

    private SharedDiscordLink parseSharedLink(JsonNode result) throws IOException {
        if (!result.path("linked").asBoolean(false)) return SharedDiscordLink.unlinked();
        String discordUserId = result.path("discordUserId").asText("").trim();
        if (!discordUserId.matches("\\d{17,20}")) {
            throw new IOException("공용 API 응답의 discordUserId가 올바르지 않습니다.");
        }
        return new SharedDiscordLink(true, discordUserId,
                result.path("discordUsername").asText("").trim(),
                result.path("discordNickname").asText("").trim());
    }

    private String discordAccountPath(UUID minecraftUuid) {
        return "/v1/accounts/" + minecraftUuid + "/discord";
    }

    private boolean isReady() {
        return jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    private String newLinkCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder result = new StringBuilder(8);
        do {
            result.setLength(0);
            for (int i = 0; i < 8; i++) result.append(alphabet.charAt(random.nextInt(alphabet.length())));
        } while (pendingLinks.containsKey(result.toString()));
        return result.toString();
    }

    private void sendMain(Player player, String message) {
        Bukkit.getScheduler().runTask(this, () -> player.sendMessage(color(message)));
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private final class DiscordListener extends ListenerAdapter {
        @Override
        public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
            if (event.getName().equals("연동확인")) completeLink(event);
        }
    }

    private record PendingLink(UUID minecraftUuid, Instant expiresAt, String requestedNickname) { }

    private record LocalDiscordLink(String discordUserId, String discordUsername, String discordNickname, boolean migrated) {
        private boolean isValid() {
            return discordUserId != null && discordUserId.matches("\\d{17,20}");
        }
    }

    private record SharedDiscordLink(boolean linked, String discordUserId, String discordUsername, String discordNickname) {
        private static SharedDiscordLink unlinked() {
            return new SharedDiscordLink(false, "", "", "");
        }
    }

    private static final class SharedApiException extends IOException {
        private final int statusCode;

        private SharedApiException(int statusCode, String message) {
            super("HTTP " + statusCode + ": " + message);
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }
}
