# Contributing to CinderLink

Thanks for wanting to help! Bug reports, fixes, translations and ideas are all welcome. This page explains
how to get set up and what we look for, so your contribution can be merged quickly.

## Before you start

- **Found a bug?** Open an issue using the **Bug report** form. Please include your `latest.log`.
- **Want to add a feature or make a big change?** Open an issue first and describe it. That way we can agree
  on the approach before you spend time on it, and nobody ends up with a pull request that can't be merged.
- **Small fixes** (typos, clear bugs, translations) can go straight to a pull request.

## Found a security problem?

Please **don't** open a public issue. Use **Security > Report a vulnerability** on the GitHub repository
instead, so it can be fixed before anyone else learns about it. This includes anything that could reveal a
player's IP address, let someone join a world they weren't invited to, or read or fake friends' status.

## CinderLink's ground rules

These are what CinderLink is about. Changes that break them won't be accepted, however useful they seem.

1. **No addresses, ever.** Players never see, copy, paste or type an IP address or port. Connection details
   stay inside encrypted records and are never shown in the UI.
2. **No IP addresses in logs.** Nothing may write an IP address to `latest.log` or `debug.log`. Use
   `SafeLog.redact(...)` for anything that might contain one.
3. **Serverless.** No CinderLink-run servers, relays or accounts. The friends list works over the public
   BitTorrent DHT, and joining is a direct connection.
4. **Respect network blocks.** If a network (school, workplace) blocks CinderLink, it shows as unavailable.
   No workarounds, proxies or fallback routes to get around blocks.
5. **Only invited friends get in.** Joining always requires a verified Minecraft account belonging to a friend
   the host picked for that session.
6. **Client-only.** CinderLink must never be required on a server.

## Getting set up

You'll need **Java 21** and Git. Any IDE works; IntelliJ IDEA is easiest for NeoForge.

1. Fork the repository and clone your fork.
2. Open the folder in your IDE and let Gradle finish importing.
3. Build and run the tests:
   ```
   gradlew build
   ```
   (In PowerShell, use `.\gradlew.bat build`.) The jar ends up in `build/libs/`.

### Testing in-game

- `gradlew runClient` and `gradlew runClient2` start two separate dev players with their own identities, so
  your real friends list is never touched.
- To test the whole friends flow without the internet, run a private test network and point both clients
  at it (see the **Building and testing** section of the README for the exact commands).
- Dev clients can't sign in to Mojang, so a few checks are relaxed in dev only. Before submitting anything
  that touches hosting or joining, please also test with real accounts if you can.

## Code guidelines

- **Keep `core/` free of Minecraft code.** Nothing in `core/` may import `net.minecraft` or `net.neoforged`.
  This keeps the engine testable without the game and makes the 1.20.1 version easy to maintain.
  Game-specific code goes in `client/`, `hosting/` or `mixin/`.
- **Add tests** for changes to `core/`. Network behaviour can be tested against the fake DHT in
  `src/test/`. All tests must pass (`gradlew test`).
- **Match the existing style:** 4-space indentation, lines up to about 120 characters, clear names, and a
  short comment wherever the *why* isn't obvious.
- **Player-facing text goes in the language file** (`src/main/resources/assets/cinderlink/lang/en_us.json`),
  never hard-coded. Write it in plain words players understand: no technical jargon.
- **Don't add dependencies** without discussing it in an issue first. CinderLink currently uses only what
  Minecraft, NeoForge and Java already provide.
- **Keep it light on the network.** Anything that runs repeatedly in the background should use the
  remembered-route fast path rather than full DHT searches.

## Translations

Translations are very welcome. Copy `en_us.json` to your language's file name (for example `de_de.json`
or `es_es.json`), translate the text on the right-hand side of each line, and open a pull request. Keep
anything like `%s` exactly as it is; that's where names and numbers are filled in.

## Pull requests

- Make one pull request per change, and keep it focused.
- Write commit messages that say what changed, for example `Fix friend list not refreshing after Accept`.
- In the pull request, describe what you changed, why, and how you tested it.
- Make sure `gradlew build` passes before submitting. The GitHub Actions check runs it too.

## License

CinderLink is released under the MIT License. By contributing, you agree that your contributions are
released under the same license.
