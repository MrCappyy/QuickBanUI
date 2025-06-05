# QuickBanUI

QuickBanUI is a Minecraft punishment plugin built for Spigot and Paper servers. It replaces command-based moderation with a full GUI-based system to streamline staff workflows and reduce mistakes during punishment.

## Features

- GUI interface for banning, muting, kicking, and warning players
- Predefined and editable punishment reasons
- Duration selector for temporary bans and mutes
- Silent punishment mode (does not broadcast actions)
- Per-player punishment history with timestamps
- Staff analytics system for moderation statistics
- Optional MySQL storage for network-wide syncing
- Discord webhook integration for logging
- Automatic backup system and update checker
- File-based YAML storage by default

## Commands

- `/punish <player>` – Opens the main punishment GUI
- `/ban`, `/mute`, `/kick`, `/warn <player>` – Quick access commands
- `/unban <player>`, `/unmute <player>` – Remove active punishments
- `/history <player>` – View full punishment history
- `/qb reload`, `/qb analytics`, `/qb backup`, `/qb reasons` – Admin tools

## Setup

1. Place the compiled plugin into your server's `/plugins` directory
2. Start the server to generate configuration files
3. Edit `config.yml` as needed (e.g., enable MySQL)
4. Use `/punish <player>` to begin using the system

## Building

To build the plugin from source:

```bash
git clone https://github.com/MrCappyy/QuickBanUI.git
cd QuickBanUI
mvn clean install
````

The compiled `.jar` will be located in the `target/` directory.

## SpigotMC Resource

The compiled plugin is available on SpigotMC:
[https://www.spigotmc.org/resources/quickbanui.XXXXX/](https://www.spigotmc.org/resources/quickbanui.XXXXX/)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

Resale, repackaging, or redistribution of the plugin without permission is not allowed.

© 2024 MrCappy