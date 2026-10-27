**Make your server’s final warning unforgettable.**

BanCinematic brings cinematic bans and moderation tools to Minecraft Paper servers. Deliver a dramatic send-off featuring a towering Warden, animations, and visual effects while keeping everyday moderation simple.

## Features

- Cinematic bans featuring a Warden by default.
- Permanent and temporary bans.
- Permanent and temporary chat mutes.
- Kick commands for immediate player removal.
- Punishment history with page navigation.
- Player checks showing identity, last seen, and active punishments.
- Player name or UUID support for removing bans and mutes.
- Tab completion suggesting actively banned or muted players.
- Customizable messages and cinematic settings.
- Portuguese and English messages.
- Individual command permissions.
- Local SQLite storage, with optional MySQL support.
- Optional synchronization between servers sharing the same MySQL database.

## Commands

/ban <player> <reason>
Permanently ban a player.

/tempban <player> <duration> <reason>
Temporarily ban a player.

/unban <player|UUID>
Remove a player's ban.

/mute <player> <reason>
Permanently mute a player.

/tempmute <player> <duration> <reason>
Temporarily mute a player.

/unmute <player|UUID>
Remove a player's mute.

/kick <player> <reason>
Disconnect a player from the server.

/history <player> [page]
View a player's punishment history.

/check <player>
View a player's identity, last seen, and active punishments.

## Duration Formats

- s — Seconds, such as 30s
- m — Minutes, such as 10m
- h — Hours, such as 2h
- d — Days, such as 7d
- w — Weeks, such as 1w

## Permissions

bancinematic.ban
bancinematic.tempban
bancinematic.unban
bancinematic.mute
bancinematic.tempmute
bancinematic.unmute
bancinematic.kick
bancinematic.history
bancinematic.check

## Installation

1. Stop your Paper server.
2. Place the BanCinematic .jar file in the server's plugins folder.
3. Start the server to generate the configuration files.
4. Customize the settings and grant command permissions to your staff.
5. Restart the server to apply configuration changes.

SQLite is enabled by default and requires no separate database server. Required database libraries download automatically on first startup, which requires an internet connection.

## Compatibility

BanCinematic was validated against Paper 1.21.11, 26.1.2, 26.2, and 26.3 using SQLite and MySQL.

Use Java 21 for Paper 1.21.11 and Java 25 for Paper 26.1 or later.

Future Paper releases require validation.
