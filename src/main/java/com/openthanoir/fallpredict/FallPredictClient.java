package com.openthanoir.fallpredict;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minecraft 1.21.11, Fabric, Mojang mappings. Client-side only; shows OTHER players.
 *
 * For every airborne player (falling, jumping, gliding, just took off their elytra...) it
 * predicts where they will be HORIZON ms from now, using their measured movement and vanilla
 * air physics, and draws a box there plus a line from where they are shown now.
 * Default horizon = YOUR ping, i.e. where they will be by the time your action reaches the server.
 */
public class FallPredictClient implements ClientModInitializer {

    // ---- settings (edit and rebuild) ----
    static final int HORIZON_MS_OVERRIDE = -1;  // -1 = use your own ping; or set e.g. 80
    static double extraMs = 30;                 // added to the horizon; adjust in game with [ and ]
    static final double MAX_RANGE = 96;
    static final double MIN_SPEED = 0.6;        // blocks/tick; ignores jumping/walking, keeps fast falls + elytra
    static final double AIM_CONE_DEG = 35;      // only the player nearest your crosshair within this cone
    static final int MAX_TARGETS = 1;
    static final int DRAW_MS = 60;              // gizmo lifetime; redrawn every tick (50 ms)
    static final int COLOR_BOX = 0xFF33E6FF;

    static boolean enabled = true;
    static KeyMapping toggleKey, moreKey, lessKey;
    static final Map<UUID, Vec3> lastPos = new HashMap<>();

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("fallpredict", "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.fallpredict.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        moreKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.fallpredict.more", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, category));
        lessKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.fallpredict.less", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, category));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            if (mc.player != null) mc.player.displayClientMessage(
                    Component.literal("Fall Predict: " + (enabled ? "ON" : "OFF")), true);
        }
        if (mc.level == null || mc.player == null) { lastPos.clear(); return; }
        boolean changed = false;
        while (moreKey.consumeClick()) { extraMs += 10; changed = true; }
        while (lessKey.consumeClick()) { extraMs -= 10; changed = true; }
        if (changed) {
            double h = (HORIZON_MS_OVERRIDE >= 0 ? HORIZON_MS_OVERRIDE : latency(mc, mc.player)) + extraMs;
            mc.player.displayClientMessage(Component.literal(
                    String.format("Fall Predict horizon: %.0f ms (extra %.0f)", h, extraMs)), true);
        }

        List<? extends Player> players = mc.level.players();
        double horizonMs = Math.max(0, (HORIZON_MS_OVERRIDE >= 0 ? HORIZON_MS_OVERRIDE : latency(mc, mc.player)) + extraMs);
        double ticks = horizonMs / 50.0;

        record Cand(AABB end, double dot) {}
        List<Cand> cands = new java.util.ArrayList<>();
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        double minDot = Math.cos(Math.toRadians(AIM_CONE_DEG));

        for (Player p : players) {
            Vec3 pos = p.position();
            Vec3 prev = lastPos.put(p.getUUID(), pos);

            if (!enabled || p == mc.player || prev == null || ticks <= 0) continue;
            if (p.distanceToSqr(mc.player) > MAX_RANGE * MAX_RANGE) continue;
            if (p.onGround() || p.isSpectator() || p.isPassenger() || p.isInWater() || p.isInLava()
                    || p.onClimbable()) continue;

            boolean gliding = p.isFallFlying();
            double g = p.hasEffect(MobEffects.SLOW_FALLING) ? 0.01 : 0.08;
            Vec3 d = pos.subtract(prev); // movement during the last tick
            if (d.length() < MIN_SPEED) continue; // not a fast fall / glide
            Vec3 dir = p.getBoundingBox().getCenter().subtract(eye).normalize();
            double dot = dir.dot(look);
            if (dot < minDot) continue;           // not near your crosshair

            // Velocity for the NEXT tick. Gliding: keep it (elytra changes slowly).
            // Not gliding (incl. the moment they take the elytra off): vanilla air physics, momentum kept.
            Vec3 vel = gliding ? d : new Vec3(d.x * 0.91, (d.y - g) * 0.98, d.z * 0.91);

            cands.add(new Cand(predict(mc.level, p, pos, vel, ticks, gliding, g), dot));
        }
        cands.sort((a, b) -> Double.compare(b.dot, a.dot));
        for (int i = 0; i < Math.min(MAX_TARGETS, cands.size()); i++) {
            Gizmos.cuboid(cands.get(i).end, GizmoStyle.stroke(COLOR_BOX)).setAlwaysOnTop().persistForMillis(DRAW_MS);
        }
        lastPos.keySet().retainAll(players.stream().map(Player::getUUID).toList());
    }

    private static int latency(Minecraft mc, Player p) {
        if (mc.getConnection() == null) return 0;
        PlayerInfo info = mc.getConnection().getPlayerInfo(p.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    /** Where the player's hitbox will be after `ticks` ticks (fractional ticks allowed). */
    private static AABB predict(Level level, Player p, Vec3 startPos, Vec3 startVel,
                                double ticks, boolean gliding, double gravity) {
        AABB box = p.getBoundingBox().move(startPos.subtract(p.position()));
        Vec3 vel = startVel;
        int whole = (int) Math.floor(ticks);
        double frac = ticks - whole;

        for (int i = 0; i < whole + (frac > 0 ? 1 : 0); i++) {
            double f = i < whole ? 1.0 : frac;
            Vec3 move = vel.scale(f);
            Vec3 adj = Entity.collideBoundingBox(p, move, box, level, List.of());
            box = box.move(adj);
            if (adj.x != move.x) vel = new Vec3(0, vel.y, vel.z);
            if (adj.y != move.y) vel = new Vec3(vel.x, 0, vel.z);
            if (adj.z != move.z) vel = new Vec3(vel.x, vel.y, 0);
            if (f == 1.0 && !gliding) {
                vel = new Vec3(vel.x * 0.91, (vel.y - gravity) * 0.98, vel.z * 0.91);
            }
        }
        return box;
    }
}
