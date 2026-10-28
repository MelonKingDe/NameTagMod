# NameTagMod by MelonKingDe (Fabric, Minecraft 26.2)

Client-side mod: extra lines above/below your own nametag
(visible only to you, in third-person view F5).

## Usage
- Editor: press K (changeable under Controls), click the "NameTagMod" button in the game menu (ESC) or run /nametag gui
- Per line: text, scale (slider 0.25x - 3x), vertical shift (slider -30 to +30 px) and an Icon button
- Shift: +5 moves the line 5 pixels up, -5 moves it 5 pixels down. Only that line moves, the others stay.
- The Icon button inserts {icon:name} at the cursor position in the text box
  (so before, after or in the middle of the text). Several icons per line are possible.
- Color codes with &, e.g. "&6Gold &lBold". Colors continue after an icon.
- &z = chroma (rainbow) effect. It animates and can be combined with &l, &o, &n, &m, &k.
  Any color code (&0-&f) or &r ends it. Example: "&z&lNameTagMod &rplain".

## Custom icons
Put PNG or GIF files into  .minecraft/config/nametaglines/icons
The file name becomes the icon name (lowercase; only a-z 0-9 _ -; other characters become _).
Click "Reload" in the icon picker or run /nametag icons reload.
Icons are scaled to the line height (aspect ratio is kept).
Animated GIFs play automatically (frame delays, transparency and frame disposal are supported).
Limits: at most 200 frames and 8192 px total height of all frames stacked (frames beyond that are
dropped); big GIFs use a lot of memory, so keep them small (e.g. 32x32 or 64x64).
If a GIF does not load, check the game log for "Could not load icon".

## Collection lookup (Elite SkyBlock)
Write `<collection:Wilted Berberis>` into a line. The mod looks up your own collection amount and
rank on the matching leaderboard (https://eliteskyblock.com/leaderboard/wilted-berberis) and shows
e.g. `12,345 (#42)` in its place. The name is turned into the slug (lowercase, spaces -> hyphens).
It can be mixed with text, colors, &z and icons: "&6Berberis: &f<collection:Wilted Berberis>".
- While loading it shows `...`, `no rank` if you are not on the leaderboard, `n/a` on errors.
- Requests run in the background, are cached (default 5 minutes) and never block the game.
- Your Minecraft UUID is sent to the Elite API (api.eliteskyblock.com) for this feature.
- Uses your selected SkyBlock profile; override with /nametag collection profile <profileId>
  (and `auto` to switch back).
- Endpoints, display format and refresh time are in config/nametaglines.json
  (collectionApiBase, collectionProfilePath, collectionRankPath, collectionFormat, ...).
- If nothing shows up, run /nametag collection test Wilted Berberis and check the output.

## Skill lookup
`<skill:social>` works like the collection token but for skill leaderboards, e.g.
https://eliteskyblock.com/leaderboard/social -> shows e.g. `1,234,567 XP (#42)`.
The format can be changed with `skillFormat` in config/nametaglines.json.
Everything said about collections (caching, profile, test command, endpoints) applies here too.

## Slayer lookup
`<slayer:vampire>` works like the skill token but for slayer leaderboards, e.g.
https://eliteskyblock.com/leaderboard/vampire-slayer -> shows e.g. `250,000 XP (#42)`.
Available: `<slayer:zombie>`, `<slayer:spider>`, `<slayer:wolf>`, `<slayer:enderman>`, `<slayer:blaze>`,
`<slayer:vampire>`. The boss names work too (revenant, tarantula, sven, voidgloom, inferno, riftstalker),
and so does the full name like `<slayer:vampire-slayer>`.
The format can be changed with `slayerFormat` in config/nametaglines.json. Test with
/nametag collection test vampire-slayer. It also works in the "Other players" line.

## Limits
Up to 8 lines above and 8 lines below, 256 characters per line. If they do not fit on the screen,
scroll with the ^ / v buttons in the editor.

## Line above other players
The "Other players" button in the editor (top right) sets ONE line that is shown above the nametags of
other players near you (only you see it). It can contain `{player}` (their name) and
`<skill:...>` / `<collection:...>` / `<slayer:...>`, which are looked up for each player, plus colors, &z, icons, scale and shift.
- Off by default. Settings: max distance (4-64 blocks) and max number of players (1-20, the nearest ones).
- Only real players are used (NPCs are skipped). Their UUIDs are sent to the Elite API for the lookups.
- Commands: /nametag others on|off, /nametag others text <text>
- Example: `&7{player} &8| &f<skill:social>`

## Tutorial
The "Tutorial" button in the editor (top left) explains all features and what you can write into a line.

## Social counts
Write `<youtube:MelonKingDe>`, `<twitch:Name>` or `<tiktok:Name>` into a line to show the subscriber /
follower count of that channel. The leading @ is optional. Example: "&c<youtube:MelonKingDe> subs".
- **YouTube** needs no key: the mod reads the public channel page (`youtubeUrl`) and searches it with
  `youtubeRegex`. YouTube shows rounded numbers (e.g. 1.23K = 1,230). This can break when YouTube changes
  the page, blocks the request or the channel hides its subscriber count (it then shows `n/a`).
  For better reliability you can add your own YouTube Data API v3 key (Google Cloud Console, enable
  "YouTube Data API v3") as `youtubeApiKey` in config/nametaglines.json; then the official API is used.
  Do not share your config file if it contains a key.
- **Twitch** uses the public DecAPI follower counter (`twitchUrl` in the config, must return only a number).
- **TikTok** has no public API: the mod reads the public profile page and searches it with `tiktokRegex`.
  TikTok often blocks this; it then shows `blocked`. Both URL and regex can be changed in the config.
- While loading it shows `...`; errors show a short text (`bad API key`, `not found`, `blocked`, `n/a`).
- Counts are cached (`socialRefreshMinutes`, default 10) and fetched in the background.
- Options: `socialFormat` (e.g. "{count} fans") and `socialCompact` (1.2K / 3.4M).
- Test with /nametag social test youtube MelonKingDe (or twitch / tiktok).

## View modes
- **nametag** (default): the lines are part of the world nametag. They follow every view effect
  exactly (view bobbing, FOV changes, hurt/nausea camera effects, perspective), but water, stained
  glass and item frames can dim or cover them, like with vanilla nametags.
- **overlay**: the lines are projected onto the screen and drawn on top of everything, so they are
  always fully visible. Limits: hidden with F1, shown through blocks, FOV/bobbing effects are not followed exactly.
Switch with the "View" button in the editor or /nametag mode nametag|overlay.

## Commands
  /nametag above|below add <text>
  /nametag above|below set <nr> <text>
  /nametag above|below scale <nr> <factor>
  /nametag above|below offset <nr> <pixels>   (+ = up, - = down)
  /nametag above|below remove <nr>
  /nametag above|below clear
  /nametag collection test <name or slug> | profile <id|auto> | refresh   (test also works for skills, e.g. 'social')
  /nametag social test <youtube|twitch|tiktok> <name> | refresh
  /nametag others on|off | text <text>
  /nametag mode nametag|overlay
  /nametag list | toggle | ownname | gui | icons | icons reload

Config: config/nametaglines.json (the old format with "above"/"below" is not migrated).

## Building
  gradle wrapper && ./gradlew build   (JDK 25)
Finished mod: build/libs/NameTagMod.jar (not the -sources.jar).
Without a local setup: upload as a GitHub repo -> Actions -> artifact "NameTagMod-jar".

## If the build fails on API names
- Fix the imports of CameraRenderState / RenderType / RenderTypes via your IDE
- ClientCommands <-> ClientCommandManager
- Position/direction of the lines in NametagRenderer (y grows downwards)
- Overlay mode: lines too high/low or at the wrong place -> check the projection in NametagRenderer.draw()
