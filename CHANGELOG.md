# Changelog

## 1.3.0

**Death leaderboard**
- `!deaths` in Discord and `/deaths` in-game rank everyone by deaths, with each player's nemesis
  (the mob that killed them most), who is still clean, and who has gone longest without dying
- Counts come from the game's own statistics, so they include every death since the world began.
  Online players' stats are saved first, so a death a moment ago already counts
- When death cards have their own channel, `!deaths` is answered there too. Nothing else said in
  that channel reaches the game, and one leaderboard per channel per 10 seconds
- Fabric and NeoForge. Paper replies that it does not report death stats yet

**Bedrock heads**
- Bedrock players joining through Geyser and Floodgate showed as Steve on every card and chat
  message. Their real skin is now found through GeyserMC's global API and drawn by
  `bedrock-avatar-url` (default nmsr.nickac.dev). Lookups are cached and made off the server thread;
  if one fails, the old avatar is used

**Fixes**
- Death cards now use the exact wording players saw in chat. Some deaths read differently before,
  e.g. "hit the ground too hard" where chat said "fell from a high place": Fabric read the message
  after vanilla had reset the combat tracker, and NeoForge read it from the damage source

## 1.2.0

- `deaths-webhook-url` sends death cards to their own channel, e.g. a public one while joins,
  advancements and server status stay private. Blank keeps them on `webhook-url` as before, and a
  blank `webhook-url` still switches every post off, this one included

## 1.1.0

**Update cards** (Fabric)
- On each start, the game version and installed mods are compared with the last start, and a card
  lists what was added, updated or removed, so players hear about an update without anyone posting it
- **On by default** (`relay-updates`). Turning it off stops the cards but not the tracking, so switching
  it back on reports only the next change
- Only top-level mods count: jar-in-jar libraries and Minecraft/Java themselves are left out
- The last start is kept in `sculklink-installed.json` beside the config. A first start only records
  a baseline, so installing the mod does not announce every mod as new. A damaged file is replaced
  by a fresh baseline, and a failed update check is logged without stopping the server
- A card too long for one Discord embed is split across several, never cut off
- NeoForge and Paper do not report their mods yet, so they post no update cards

**Per-event channels**
- `join-leave-webhook-url` sends join/leave cards to their own channel
- `updates-webhook-url` does the same for update cards
- Both default to blank, which keeps everything on `webhook-url` as before. A blank `webhook-url`
  still switches every post off, these included

**Fixes**
- Earning an advancement on Minecraft 26.3 crashed the server (`NoSuchMethodError: DisplayInfo.shouldAnnounceChat`),
  on Fabric and NeoForge: 26.3 made `DisplayInfo` a record and renamed its accessors. They are now looked
  up by name, newest first, so one jar runs on 26.2 and 26.3, and a failure relaying an advancement is
  logged instead of being thrown into Minecraft's advancement code
- Webhook URLs are trimmed, and an explicit `null` is read as blank. A URL that cannot be parsed is
  reported once by its config key and skipped; it is no longer retried, and never written to the log,
  where it would expose the webhook's token
- A post that still fails after three tries is now logged

## 1.0.0

First release. Two-way Discord bridge for Minecraft 26.2 on **Fabric, NeoForge and Paper**.

**Platforms**
- Split into `core` (no server API anywhere), `fabric`, `neoforge` and `paper` modules
- One `BridgePlatform` interface — five methods — is the whole surface a new platform needs
- `core` compiles to Java 21 so its classes load on any server able to run either platform
- Only Fabric needs a mixin; NeoForge and Paper both have real advancement events
- Spigot is not supported; Paper's chat and advancement APIs have diverged too far for one jar

**Minecraft → Discord**
- Player chat, posted with the player's own name and skin head as the avatar
- Join and leave
- Deaths, using the game's own wording
- Advancements, matching vanilla's task / goal / challenge phrasing
- Server online and shutting down

**Account linking**
- `/link` in-game issues a single-use, expiring, `SecureRandom` code; `!link <code>` redeems it,
  either in the bridged channel or privately in a DM to the bot. Starting in Minecraft means the server has already authenticated that side
- `whitelist-on-link` whitelists players automatically when they link
- Links persist to `links.json` and survive restarts

**Management channel**
- Optional second channel where `!`-prefixed messages run as server commands
- Gated on a Discord role; **a blank `management-role-id` authorises nobody**, not everyone
- Management messages are never relayed into in-game chat, and unprefixed text never executes
- Fabric captures real command output; Paper confirms execution (Bukkit cannot intercept it)

**Moderation**
- `blocked-words` filters messages in both directions, ignoring case, spacing, punctuation and
  digit-for-letter swaps
- `inbound-messages-per-minute` caps how much one Discord user can push into the game

**Crash detection**
- Posts when the JVM exits without a clean shutdown, via a shutdown hook, so it also catches
  OOM kills rather than only logged exceptions
- Sent synchronously — the queue's daemon thread would never run during shutdown

**Mentions and commands**
- `@name` and `@role` typed in Minecraft become real Discord pings, allow-listed by resolved id
  so `@everyone` remains impossible
- Roles are read once over the REST API; users are learned from channel activity, which avoids
  requiring the privileged `GUILD_MEMBERS` intent
- `!help` lists only the commands that are enabled; management commands appear
  solely in the management channel
- `!list` in Discord replies with who is online
- `/discord` in-game prints the configured invite link
- Bot activity shows the live player count

**Discord → Minecraft**
- Channel messages relayed into in-game chat
- Server nicknames preferred over display names
- User mentions resolved to readable names; custom emoji collapsed to `:name:`
- Image-only posts announced as `[attachment]`

**Behaviour worth calling out**
- Webhook posts are ignored on the way back in, so the bridge cannot loop with itself
- `allowed_mentions` is empty on every outbound post — `@everyone` typed in-game cannot ping
  Discord
- Section signs from Discord are neutralised before reaching in-game chat
- Outbound sending is queued off the server thread; a slow Discord cannot lag the game
- Gateway sessions resume after a drop, with exponential backoff and heartbeat-based detection
  of dead-but-open sockets
- A rejected token or missing MESSAGE CONTENT intent disables the inbound half with a message
  naming the setting to fix, rather than retrying forever
