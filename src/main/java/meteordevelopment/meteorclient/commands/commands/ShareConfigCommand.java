/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.commands.commands;

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.commands.MeteorClickEvent;
import meteordevelopment.meteorclient.commands.arguments.ModuleArgumentType;
import meteordevelopment.meteorclient.commands.arguments.PlayerListEntryArgumentType;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.command.CommandSource;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;
import static meteordevelopment.meteorclient.MeteorClient.mc;

public class ShareConfigCommand extends Command {
    private final ArrayList<ConfigShare> instances = new ArrayList<>();
    private static final ArrayList<String> messagesToHide = new ArrayList<>();
    private String selfIdentifier;
    private final static SimpleCommandExceptionType ACTIVE_INSTANCE = new SimpleCommandExceptionType(Text.literal("You already have an active instance with that player."));
    private final static SimpleCommandExceptionType A = new SimpleCommandExceptionType(Text.literal("You can't share a config with yourself."));

    public ShareConfigCommand() {
        super("share-config", "Shares a config with another meteor user.", "sc");
        MeteorClient.EVENT_BUS.subscribe(this);
        selfIdentifier = identifier(mc.getGameProfile());
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("clear").executes(context -> {
            instances.clear();
            info("Cleared active instances.");

            return SINGLE_SUCCESS;
        }));

        builder.then(argument("player", PlayerListEntryArgumentType.create()).then(argument("module", ModuleArgumentType.create()).executes(context -> {
            PlayerListEntry player = PlayerListEntryArgumentType.get(context);
            if (player.equals(mc.player.playerListEntry)) throw A.create();

            Module module = ModuleArgumentType.get(context);

            for (ConfigShare instance : instances) {
                if (instance.partner.equals(player)) throw ACTIVE_INSTANCE.create();
            }

            instances.add(new ConfigShare.Sharer(player, module));

            return SINGLE_SUCCESS;
        })));
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        instances.clear();
    }

    @EventHandler
    private void onGameJoin(GameJoinedEvent event) {
        selfIdentifier = identifier(mc.getGameProfile());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        instances.forEach(ConfigShare::tick);
        instances.removeIf(instance -> instance.stage == ConfigShare.Stage.RESOLVED);
    }

    // todo WHY THE FUCK ARE MESSAGES GETTING OVERWRITTEN WHEN BETTER CHAT ENABLED IS ENABLED????
    // todo you get kicked from cpvp.cc as soon as you start sending config messages, is that an issue with us or them?
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onChatMessage(ReceiveMessageEvent event) {
        String str = event.getMessage().getString();

        Iterator<String> it = messagesToHide.iterator();
        while (it.hasNext()) {
            if (str.contains(it.next())) {
                event.cancel();
                it.remove();
                return;
            }
        }

        // (partnerIdentifier).(own username).(message type).(message)
        // check relevancy
        int index = str.indexOf(selfIdentifier);
        if (index == -1) return;

        // ensure it's long enough
        str = str.substring(index);
        String[] split = str.split("\\.");
        if (split.length < 4) return;

        // ensure they included their name
        PlayerListEntry partner = mc.getNetworkHandler().getPlayerListEntry(split[1]);
        if (partner == null) return;

        // ensure the message type is actually valid
        int messageType;
        try {
            messageType = Integer.parseInt(split[2]);
        } catch (NumberFormatException e) {
            return;
        }

        if (messageType < 1 || messageType > 3) return;

        if (messageType == 1) { // todo ensure there are no prospects for crashes
            if (split.length != 5) return;

            for (ConfigShare instance : instances) {
                if (instance.partner.equals(partner)) {
                    error("'%s' tried to create a new share request when one is already present.", split[1]);
                }
            }

            int sections;
            try {
                sections = Integer.parseInt(split[3]);
            } catch (NumberFormatException e) {
                return; // bad data
            }

            Module module = Modules.get().get(split[4]);
            if (module == null) return; // bad data

            ConfigShare.Recipient recipient = new ConfigShare.Recipient(partner, module, sections);
            instances.add(recipient);

            info(String.format("Player %s wants to share their '%s' config with you (%s sections)", split[1], module.title, split[3]));
            info(getResponseButtons(recipient));
        }
        else {
            for (ConfigShare cs : instances) {
                if (cs.partner.equals(partner)) {
                    cs.onMessage(split);
                }
            }
        }

        event.cancel();
    }

    private static String identifier(GameProfile p) {
        return String.valueOf(Math.abs(p.getId().hashCode())).substring(0, 3);
    }

    // todo this appears to cause a small memory leak, because while ConfigShare instances are cleared from the list when
    //  they're resolved, they dont actually get deleted from memory. This is confirmed by the println calls still working
    //  even after the instance is resolved. I assume this is because we're packing them up in the click events here,
    //  forcing them to stay. Should probably figure out a fix before merging.
    private MutableText getResponseButtons(ConfigShare.Recipient recipient) {
        MutableText response = Text.literal("");

        MutableText accept = Text.literal("[ACCEPT]");
        accept.setStyle(accept.getStyle()
            .withFormatting(Formatting.GREEN)
            .withClickEvent(new MeteorClickEvent(() -> {
                System.out.println(recipient);
                if (recipient.stage == ConfigShare.Stage.R_AWAITING_DECISION) {
                    recipient.response("accept");
                    recipient.stage = ConfigShare.Stage.R_RECEIVING_CONFIG;
                }
            }))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                Text.literal("Accepts the other players request, and tells them to start sending the config.").formatted(Formatting.GRAY))
            )
        );

        response.append(accept).append("    ");

        MutableText deny = Text.literal("[DENY]");
        deny.setStyle(accept.getStyle()
            .withFormatting(Formatting.RED)
            .withClickEvent(new MeteorClickEvent(() -> {
                System.out.println(recipient);
                if (recipient.stage == ConfigShare.Stage.R_AWAITING_DECISION) {
                    recipient.response("deny");
                    recipient.stage = ConfigShare.Stage.RESOLVED;
                }
            }))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                Text.literal("Denies the other players request.").formatted(Formatting.GRAY))
            )
        );

        return response.append(deny);
    }

    public abstract static class ConfigShare {
        public PlayerListEntry partner;

        // this should handle both specifying who the message is meant for and adding a basic proof of work, both to
        // try and deal with servers not having consistent whisper formats and to kneecap low effort bad actors
        public String partnerIdentifier;
        public Module module;
        public int sections, ticks;
        public String[] config;
        public Stage stage;

        public abstract void tick();

        public abstract void onMessage(String[] message);

        public void sendMessage(int messageType, String message) {
            // message structure:
            // (partnerIdentifier).(own username).(message type).(message)
            // todo try to figure out a more space efficient way to indicate yourself

            /*
                message types:
                    1. request to send a config
                    2. generic response, context will be in actual message
                    3. config sections
             */

            String toSend = partnerIdentifier + "." + mc.getGameProfile().getName() + "." + messageType + "." + message;
            messagesToHide.add(toSend);

            // /w is more space efficient but doesn't appear to be universally present, for example it's not there in
            // the minehut lobby, so going with /msg is probably safer
            mc.getNetworkHandler().sendChatCommand("msg " + partner.getProfile().getName() + " " + toSend);
        }

        public void response(String arg) {
            sendMessage(2, arg);
        }

        public static class Sharer extends ConfigShare {
            public Sharer(PlayerListEntry partner, Module module) {
                this.partner = partner;
                this.partnerIdentifier = identifier(partner.getProfile());
                this.module = module;
                this.config = getConfig();
                this.stage = Stage.S_SENDING_REQUEST;
            }

            public void tick() {
                switch (stage) {
                    case S_SENDING_REQUEST -> {
                        sendMessage(1, sections + "." + module.name);
                        ChatUtils.info("Sent config share request. Awaiting response.");
                        this.stage = Stage.S_AWAITING_ACCEPTANCE;
                        ticks = -1;
                    }
                    case S_AWAITING_ACCEPTANCE -> {
                        if (ticks >= 340) { // a little longer to account for lag and such
                            ChatUtils.warning("Timed out while waiting for the other player to respond.");
                            response("stop");
                            this.stage = Stage.RESOLVED;
                        }
                    }
                    case S_SENDING_CONFIG -> {
                        // iterate through the config list and send each one
                        // make sure to wait a second for anti-spam plugins

                        if (ticks % 20 == 0) {
                            sendMessage(3, ticks / 20 + "." + config[ticks / 20]);
                            ChatUtils.sendMsg(this.hashCode(), Formatting.GRAY, "Sending config section %s/%s", (ticks / 20) + 1, config.length);
                        }

                        if (((double) ticks) / 20 == config.length - 1) {
                            ChatUtils.info("Finished sending config");
                            this.stage = Stage.RESOLVED;
                            return;
                        }
                    }
                }

                ticks++;
            }

            public void onMessage(String[] message) {
                // (partnerIdentifier).(own username).(message type).(message)

                if (message[2].equals("2")) {
                    String partnerName = partner.getProfile().getName();

                    if (message[3].equals("stop")) this.stage = Stage.RESOLVED;

                    else if (this.stage == Stage.S_AWAITING_ACCEPTANCE) {
                        switch (message[3]) {
                            case "accept" -> ChatUtils.info("%s accepted your share request.", partnerName);
                            case "deny" -> ChatUtils.info("%s denied your share request.", partnerName);
                            case "timeout" -> ChatUtils.info("%s timed out.", partnerName);
                            default -> ChatUtils.warning("Unknown response '%s' from player '%s'", message[3], partnerName);
                        }

                        this.stage = message[3].equals("accept") ? Stage.S_SENDING_CONFIG : Stage.RESOLVED;
                        this.ticks = -1;
                    }
                }
            }

            public String[] getConfig() {
                String pre = ("msg " + partner.getProfile().getName() + " " + partnerIdentifier + "." + mc.getGameProfile().getName() + ".3.0."); // an example of a config section message
                int charsAvailable = 256 - pre.length();
                String nbt = module.toTag().asString();

                this.sections = (int) Math.ceil((double) nbt.length() / charsAvailable);
				return Iterables.toArray(Splitter.fixedLength(charsAvailable).split(nbt), String.class);
            }
        }

        public static class Recipient extends ConfigShare {
            public Recipient(PlayerListEntry partner, Module module, int sections) {
                this.partner = partner;
                this.partnerIdentifier = identifier(partner.getProfile());
                this.module = module;
                this.sections = sections;
                this.config = new String[sections];
                this.stage = Stage.R_AWAITING_DECISION;
            }

            public void tick() {
                if (ticks >= 300) { // 15s timeout
                    response("stop");

                    switch (this.stage) {
                        case R_AWAITING_DECISION -> ChatUtils.warning("Request timed out.");
                        case R_RECEIVING_CONFIG -> ChatUtils.warning("Timed out waiting for config sections.");
                    }

                    this.stage = Stage.RESOLVED;
                }

                ticks++;
            }

            public void onMessage(String[] message) {
                // (partnerIdentifier).(own username).(message type).(message)

                if (message[2].equals("2")) {
                    if (message[3].equals("stop")) this.stage = Stage.RESOLVED;
                }

                if (message[2].equals("3")) {
                    String partnerName = partner.getProfile().getName();

                    if (this.stage != Stage.R_RECEIVING_CONFIG) {
                        ChatUtils.error("Player '%s' sent a message out of order.", partnerName);
                        this.stage = Stage.RESOLVED;
                        response("stop");

                        return;
                    }

                    int i;
                    try {
                        i = Integer.parseInt(message[3]);
                    } catch (NumberFormatException e) {
                        ChatUtils.error("Received invalid data from player '%s'.", partnerName);
                        this.stage = Stage.RESOLVED;
                        response("stop");

                        return;
                    }

                    this.config[i] = String.join(".", Arrays.copyOfRange(message, 4, message.length));
                    this.ticks = 0;

                    if (i == this.config.length - 1) {
                        ChatUtils.info("Received all config sections.");

                        String nbt = String.join("", config);

                        module.settings.reset();

                        try {
                            module.fromTag(StringNbtReader.parse(nbt));
                            ChatUtils.info("Applied module config successfully.");
                        } catch (Exception e) {
                            ChatUtils.error("Unable to apply module config.");
                        }

                        this.stage = Stage.RESOLVED;
                    }
                }
            }
        }

        public enum Stage {
            //sender
            S_SENDING_REQUEST,
            S_AWAITING_ACCEPTANCE,
            S_SENDING_CONFIG,

            //recipient
            R_AWAITING_DECISION,
            R_RECEIVING_CONFIG,

            //shared
            RESOLVED
        }
    }
}
