/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.commands;

import net.minecraft.text.ClickEvent;

public class MeteorClickEvent extends ClickEvent {
    // this is kinda silly but atm I don't know how else best to allow arbitrary code execution on a click event
    // todo find a better package to put this in

    public final Runnable runnable;
    public MeteorClickEvent(Runnable runnable) {
        super(null, null); // Should ensure no vanilla code is triggered, and only we handle it
        this.runnable = runnable;
    }
}
