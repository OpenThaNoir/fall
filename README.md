# Fall Predict 1.2.0 (Fabric, Minecraft 1.21.11, client-side)

Shows where OTHER airborne players (falling, jumping, gliding, just took off their elytra) will be
in a short time, by default YOUR ping (e.g. 50 ms). A cyan box marks that predicted spot and a line
joins it to where they are shown now. Visible through walls. Press K to toggle.

## Build
1. JDK 21 + a recent Gradle (run `gradle wrapper --gradle-version latest` once, then `gradlew build`).
2. Put build/libs/fallpredict-1.2.0.jar in your 1.21.11 Fabric mods folder with Fabric API 0.141.x+1.21.11.

Settings (ping override/scale, range, colors) are constants at the top of FallPredictClient.java.
