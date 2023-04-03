/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement.speed.modes;

import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Anchor;
import meteordevelopment.meteorclient.systems.modules.movement.speed.SpeedMode;
import meteordevelopment.meteorclient.systems.modules.movement.speed.SpeedModes;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import org.joml.Vector2d;

public class Strafe extends SpeedMode {

    public Strafe() {
        super(SpeedModes.Strafe);
    }

    private long timer = 0L;
    private StrafeMode stage = StrafeMode.Jump;

    @Override
    public void onMove(PlayerMoveEvent event) {
        switch (stage) {
            case Jump -> {
                if (!PlayerUtils.isMoving() || !mc.player.isOnGround()) break;

                ((IVec3d) event.movement).setY(getHop(0.40123128));
                speed = 1.18f * getDefaultSpeed() - 0.01;
                speed *= settings.ncpSpeed.get();

                stage = StrafeMode.Slowdown;
            }
            case Slowdown -> {
                speed = distance - 0.76 * (distance - getDefaultSpeed());
                stage = StrafeMode.Fall;
            }
            case Fall -> {  // Reset on collision or predict and update speed
                if (!mc.world.isSpaceEmpty(mc.player.getBoundingBox().offset(0.0, mc.player.getVelocity().y, 0.0)) || mc.player.verticalCollision && stage != StrafeMode.Jump) {
                    stage = StrafeMode.Jump;
                }
                speed = distance - (distance / 159.0);
            }
        }

        speed = Math.max(speed, getDefaultSpeed());

        if (settings.ncpSpeedLimit.get()) {
            if (System.currentTimeMillis() - timer > 2500L) {
                timer = System.currentTimeMillis();
            }

            speed = Math.min(speed, System.currentTimeMillis() - timer > 1250L ? 0.44D : 0.43D);
        }

        Vector2d change = transformStrafe(speed);

        Anchor anchor = Modules.get().get(Anchor.class);
        if (anchor.isActive() && anchor.controlMovement) {
            change.set(anchor.deltaX, anchor.deltaZ);
        }

        ((IVec3d) event.movement).setXZ(change.x, change.y);
    }

    @Override
    public void onTick() {
        distance = Math.sqrt((mc.player.getX() - mc.player.prevX) * (mc.player.getX() - mc.player.prevX) + (mc.player.getZ() - mc.player.prevZ) * (mc.player.getZ() - mc.player.prevZ));
    }

    private Vector2d transformStrafe(double speed) {
        float yaw = mc.player.getYaw();
        float moveForward = Math.signum(mc.player.input.movementForward);
        float moveSide = Math.signum(mc.player.input.movementSideways);

        if (moveForward == 0 && moveSide == 0) return new Vector2d(0, 0);

        float strafe = 90 * moveSide;
        strafe *= (moveForward != 0 ? moveForward * 0.5 : 1);

        yaw = yaw - strafe;
        yaw -= (moveForward < 0 ? 180 : 0);
        double yawRadians = Math.toRadians(yaw);

        return new Vector2d(-Math.sin(yawRadians) * speed, Math.cos(yawRadians) * speed);
    }

    private enum StrafeMode {
        Jump,
        Slowdown,
        Fall
    }
}
