# Mingyeol Discord API

[![Skript](https://img.shields.io/badge/Skript-2.10.2%2B-F5A623?style=for-the-badge&logo=github&logoColor=white)](https://github.com/SkriptLang/Skript)
[![Paper](https://img.shields.io/badge/Paper-1.21-2F80ED?style=for-the-badge)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![JDA](https://img.shields.io/badge/JDA-6.5-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://github.com/discord-jda/JDA)

Minecraft 계정과 Discord 계정을 **일회용 코드로 안전하게 연동**하고, 역할·별명·음성 채널·메시지까지 **Skript 한 줄로 Discord를 조작**할 수 있게 해주는 Paper 플러그인 사실 codex가 80% 만들어줘서 나도 자세히는 앙 몰라띠

```vb
on join:
    if {discord_user_id::%uuid of player%} is set:
        discord_add_role player "역할 ID"
        send_discord_message "채널 ID" "%player%님이 서버에 접속했습니다!"
```

> [!IMPORTANT]
> 이 플러그인은 단독으로 동작하지 않는 **[Skript](https://github.com/SkriptLang/Skript) 애드온**입니다.
> 서버의 `plugins/` 폴더에 **Skript 2.10.2 이상**이 먼저 설치되어 있어야 하며, 없으면 플러그인이 로드되지 않습니다.

## 요구 사항

| 구성 요소 | 버전 | 비고 |
|---|---|---|
| [Skript](https://github.com/SkriptLang/Skript) | 2.10.2 이상 | **필수** · 원본 플러그인 (`depend: [Skript]`) |
| [Paper](https://papermc.io/) | 1.21.x | Spigot/Bukkit 미지원 |
| Java | 21 이상 | |
| Discord 봇 | - | Server Members Intent 필요 |
| 공용 연동 서버 | - | 선택 · 여러 서버에서 연동을 공유할 때만 |


## 설치

1. Discord Developer Portal에서 봇을 만들고 **Server Members Intent**를 켭니다.
2. 봇을 `bot`, `applications.commands` 범위로 서버에 초대하고, 사용할 기능에 맞는 권한을 줍니다.

   | 기능 | 필요한 권한 |
   |---|---|
   | 역할 지급·생성·삭제·색상 | 역할 관리 (Manage Roles) |
   | 별명 변경 | 별명 관리 (Manage Nicknames) |
   | 마이크·스피커 차단 | Mute Members / Deafen Members |
   | 음성 채널 이동·강퇴 | 멤버 이동 (Move Members) |
   | 채널 생성·삭제 | 채널 관리 (Manage Channels) |
   | 메시지 전송 | 채널 보기 / 메시지 보내기 |

   > 봇 역할은 조작할 대상 역할·멤버보다 **위에** 있어야 합니다.

3. 서버를 실행하는 계정의 환경 변수에 봇 토큰을 넣습니다. 토큰은 `config.yml`이나 Skript 파일에 쓰지 않습니다.

   ```bash
   export DISCORD_BOT_TOKEN="봇 토큰"
   ```

4. `mingyeol_discord_api.jar`와 Skript를 `plugins/`에 넣고 서버를 한 번 실행합니다.
5. 생성된 `plugins/MingyeolDiscordApi/config.yml`에 서버 ID를 넣고 재시작합니다.

## 설정

```yaml
# 봇이 들어가 있는 Discord 서버 ID
guild-id: "PUT_DISCORD_GUILD_ID_HERE"

# 연동 코드 유효 시간(초)
link-code-expiry-seconds: 300

# 연동 완료 시 자동 지급할 역할 ID (비우면 지급 안 함)
link-completion-role-id: ""

# 여러 서버가 연동을 공유할 때만 설정 (비우면 이 서버에만 저장)
service-base-url: ""
service-shared-secret: "PUT_A_LONG_RANDOM_SHARED_SECRET_HERE"
migrate-local-links: true
join-sync-delay-ticks: 20
shared-link-refresh-seconds: 300
```

공용 API를 연결하면 연동 정보를 DB에 먼저 저장하고, 플레이어가 다른 서버에 접속할 때 Skript 변수로 자동 복원합니다. 예전 서버의 로컬 Skript 변수에만 있던 연동은 최초 접속 때 한 번 DB로 옮깁니다. 공용 API는 **Mingyeol CHZZK API** 저장소의 `service/`(Node.js 백엔드)를 함께 사용합니다.

## Skript 문법

### 연동

```vb
discord_link player                      # 연동 코드 발급
discord_link player with "Discord 별명"   # 별명이 일치할 때만 연동
discord_unlink player                    # 연동 해제
```

### 별명 · 음성

```vb
discord_setname player to "새 별명"
discord_mute player                          # 마이크 차단 (해제: discord_unmute)
discord_deafen player                        # 스피커 차단 (해제: discord_undeafen)
kick_voice_chat player                       # 음성 채널에서 내보내기
transfer_voice_room player "음성 채널 ID"     # 다른 음성 채널로 이동
```

### 역할

```vb
discord_add_role player "역할 ID"        # give_role, add_role 도 가능
discord_remove_role player "역할 ID"     # take_role, remove_role 도 가능
discord_create_role "역할 이름"
discord_delete_role "역할 ID"
discord_set_role_color "역할 ID" "#FFAA00"
```

### 채널 · 메시지

```vb
add_voice_room "카테고리 ID" "통화방 이름"
remove_voice_room "음성 채널 ID"
discord_create_text_channel "카테고리 ID" "채널 이름"
send_discord_message "채널 ID" "보낼 메시지"
```

### 이벤트

Discord 쪽 작업은 비동기로 끝나므로, 새로 만든 역할·채널의 ID는 이벤트에서 받습니다.

```vb
on discord role created:
    send "역할 생성: %created role name% (%created role id%)" to console

on discord voice room created:
    set {room::latest} to created voice room id

on discord text channel created:
    set {_channel} to created text channel id
    send_discord_message {_channel} "채널이 만들어졌습니다!"
```

| 이벤트 | 사용 가능한 값 |
|---|---|
| `discord role created` | `created role id`, `created role name` |
| `discord voice room created` | `created voice room id`, `created voice room name`, `created voice room category id` |
| `discord text channel created` | `created text channel id`, `created text channel name`, `created text channel category id` |

## Skript 변수

연동이 끝나면 아래 변수가 자동으로 저장되어, 어떤 `.sk` 파일에서든 바로 읽을 수 있습니다.

```text
{discord_user_id::<uuid>}            Discord 사용자 ID
{discord_username::<uuid>}           Discord 사용자명
{discord_nickname::<uuid>}           서버에서 보이는 별명
{discord_minecraft_uuid::<discord ID>}   반대 방향 조회 (Discord → Minecraft)
```