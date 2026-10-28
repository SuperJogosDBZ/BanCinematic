# BanCinematic 3.0.3

**Make your server’s final warning unforgettable.**

BanCinematic brings cinematic bans and moderation tools to Minecraft Paper servers, featuring a dramatic Warden send-off.

## Features

- Cinematic bans with a Warden by default.
- Permanent and temporary bans and mutes, plus kicks.
- Punishment history, player checks, and tab completion.
- Customizable cinematics and messages in English and Portuguese.
- SQLite storage and optional MySQL synchronization.

## Commands

- `/ban <player> <reason>` — Permanently ban a player.
- `/tempban <player> <duration> <reason>` — Temporarily ban a player.
- `/unban <player|UUID>` — Remove a ban.
- `/mute <player> <reason>` — Permanently mute a player.
- `/tempmute <player> <duration> <reason>` — Temporarily mute a player.
- `/unmute <player|UUID>` — Remove a mute.
- `/kick <player> <reason>` — Disconnect a player.
- `/history <player> [page]` — View punishment history.
- `/check <player>` — View identity, last seen, and active punishments.

Duration examples: `30s`, `10m`, `2h`, `7d`, `1w`. Command permissions default to server operators.

## Installation

1. Stop your Paper server and place the BanCinematic `.jar` in the `plugins` folder.
2. Start the server to generate the configuration files.
3. Configure the plugin and staff permissions, then restart the server.

SQLite is enabled by default. Database libraries download automatically on first startup, requiring an internet connection. MySQL synchronization is optional and disabled by default.

## Compatibility

Use Java 21 for Paper 1.21.11 and Java 25 for Paper 26.1 or later. Version 3.0.3 was visually tested on Paper 1.21.11 and passed SQLite and MySQL tests. Earlier revisions were tested on Paper 1.21.11, 26.1.2, 26.2, and 26.3 with SQLite and MySQL. Future Paper releases require validation.
