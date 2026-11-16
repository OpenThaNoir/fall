# Fall Predict 1.3.0 (Fabric, Minecraft 1.21.11, client-side)

Shows ONE cyan box: where the fast-falling/gliding player nearest your crosshair will be in a short time
(your ping + extra). K toggles. If the box is behind the player, press ] to look further ahead (+10 ms);
if it is ahead, press [ (-10 ms). The action bar shows the current value.

## Build
1. JDK 21 + a recent Gradle (run `gradle wrapper --gradle-version latest` once, then `gradlew build`).
2. Put build/libs/fallpredict-1.3.0.jar in your 1.21.11 Fabric mods folder with Fabric API 0.141.x+1.21.11.

Settings (ping override/scale, range, colors) are constants at the top of FallPredictClient.java.
