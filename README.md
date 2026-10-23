# CinderLink

Play your singleplayer worlds with friends over the internet. Add friends once, then see when they're online
or hosting and join them with one click. No servers, no accounts, and no addresses to copy or paste.

- **Client-only** NeoForge mod for Minecraft 1.21.1 (1.20.1 backport planned)
- **Serverless friends list** over the BitTorrent DHT: nothing to host, nothing that can go down
- **Encrypted presence:** only your friends can see you're online or hosting
- **One-click Join:** connection details travel inside encrypted records and are never shown or logged
- **Fast:** requests, accepts and hosting status typically arrive within 1-2 seconds
- **Skins:** friends' faces in the list, and your player model on the main menu (can be turned off)

## How players use it

1. **Friends** button on the title screen or pause menu. **Copy My Code** and send your Cinder Code to a friend.
2. They choose **Add Friend**, paste it, and you get a request in-game. **Accept** it, and you're friends.
3. To host: pause > **Open to LAN** > set **Friends** to *All friends*, *Chosen friends* or *Invite only*
   (it starts at *LAN only* every time) > **Start LAN World**.
4. Your friends see you **Hosting: World Name** with a **Join** button. One click and they're in.

Hosting for friends outside your network needs UPnP on the router (or IPv6). Without it, the world is shown as
"can't be reached" and only friends on your own network can join. Joining works on any normal connection.

A Cinder Code isn't an address and can't be used to find you. **Reset Code** makes old codes stop working
without affecting existing friends.

## Security and privacy

- Your identity is a keypair in `~/.cinderlink` (outside every game instance, so modpacks can never ship it).
  Your friends list and `skins.txt` (which skin each friend wears; public info only) live there too.
- Every record on the DHT is signed and encrypted. Per-friend records live at locations only that friend
  can compute. Outsiders can't find, read, forge or link them.
- While hosting, only the friends picked for that session can log in (checked against their Mojang-verified
  account before they receive any world data). Logged-in Minecraft account required.
- IP addresses are kept out of `latest.log` on both the host and joining side. Timing lines
  (`CinderLink timing:`) contain only durations and counts, so logs are safe to share for support.
- Skins are fetched from Mojang by account UUID, exactly as the game does for players you see in-game.
- Networks that block the DHT (schools, many workplaces) simply show the friends list as unavailable;
  CinderLink doesn't try to get around them.
- The one unavoidable exposure: a friend you let join connects directly, so their game knows your IP
  (as with any Minecraft multiplayer). It's never displayed to them.

## Project layout

```
src/main/java/dev/devilswarchild/cinderlink/
  CinderLink.java, CinderLinkConfig.java
  core/        loader-independent engine (NO net.minecraft / net.neoforged imports)
    identity/  keys, Cinder Codes
    dht/       read-only Mainline DHT client (BEP 5 + BEP 44)
    presence/  encrypted records, friend requests, friends store, the presence engine
    net/       UPnP port mapping, IPv6, address checks
    util/      log redaction, safe file writes
  hosting/     JoinGate (who may log in), shared by client and integrated server
  mixin/       login check, IP-free logging
  client/      screens, Open to LAN controls, hosting, joining, skins and the menu player model
```

The `core/` rule is what keeps the 1.20.1 backport cheap: only `client/` and `mixin/` get rewritten.

## Building and testing

Needs Java 21. In PowerShell, put `.\` in front of `gradlew.bat`.

- `gradlew.bat build`: runs the tests; the jar lands in `build/libs/`
- `gradlew.bat test`: just the tests (no Minecraft needed)
- `gradlew.bat runClient` / `runClient2`: two dev players ("Dev" and "CinderTester") with separate identities
  in `run/cinderlink` and `run-client2/cinderlink`, so your real `~/.cinderlink` is never touched

Dev clients can't verify accounts with Mojang, so they skip that check when hosting. Real players never do.
To test the whole friends flow offline, run a private DHT and point both clients at it:

```
java -cp build/classes/java/main;build/classes/java/test dev.devilswarchild.cinderlink.core.dht.FakeDhtMain
gradlew.bat runClient -PdhtBootstrap=127.0.0.1:<port it prints>
```

## License

MIT. See [LICENSE](LICENSE).
