FrostPVP v3.2 - Minecraft Paper 1.21.11 (Java 21)
====================================================

NEW CONFIG LOCATION:
  plugins/FrostPVP/config.yml
  plugins/FrostPVP/trusted.yml   (saved once trust lists are edited)

The data folder is named FrostPVP because plugin.yml now has `name: FrostPVP`.
The server creates the folder and config.yml automatically on first startup.

UPGRADING FROM v3.1 / PunchSnowball:
1. Stop your Minecraft server.
2. Back up your server and plugins/PunchSnowball folder.
3. REMOVE the old PunchSnowball plugin JAR from plugins/.
4. Add the new FrostPVP JAR built from this source.
5. Start the server. If plugins/FrostPVP/config.yml and trusted.yml do not
   already exist, the plugin copies the existing files from plugins/PunchSnowball.
6. The old PunchSnowball folder is left untouched as a backup.
7. Check the FrostPVP startup log and your config/trust settings.

IMPORTANT: The Java main class and package remain
`com.example.punchsnowball.PunchSnowballPlugin` by design. There is no need
for another rewrite. Existing weapons' persistent keys stay under the legacy
`punchsnowball:` namespace so that they remain recognized after renaming.

All v3.1 features remain, including six events, event selection GUI,
Frost weapons/armor, fishing rod, Stray horn, trust system and Frost particles.
No resource pack is needed.

PROJECT LAYOUT:
  src/main/java/com/example/punchsnowball/PunchSnowballPlugin.java
  src/main/java/com/example/punchsnowball/FrostEvents.java
  src/main/resources/plugin.yml
  src/main/resources/config.yml
  .github/workflows/build.yml
  build.gradle.kts
  settings.gradle.kts

GITHUB BUILD:
Commit the project and run Actions > Build Frost Plugin > Run workflow.
Download the JAR artifact after a successful build. The ZIP here is SOURCE,
not a precompiled plugin.


3.2.1 HOTFIX: Fixed FrostEvents.participants() generic type compilation error.
