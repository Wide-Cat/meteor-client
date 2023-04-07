/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.chars.Char2CharMap;
import it.unimi.dsi.fastutil.chars.Char2CharOpenHashMap;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.mixin.ChatHudAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.font.TextHandler;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.util.ChatMessages;
import net.minecraft.client.util.TextCollector;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class BetterChat extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgLongerChat = settings.createGroup("Longer Chat");
    private final SettingGroup sgPrefix = settings.createGroup("Prefix");
    private final SettingGroup sgSuffix = settings.createGroup("Suffix");

    private final Setting<Boolean> annoy = sgGeneral.add(new BoolSetting.Builder()
        .name("annoy")
        .description("Makes your messages aNnOyInG.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> fancy = sgGeneral.add(new BoolSetting.Builder()
        .name("fancy-chat")
        .description("Makes your messages fancy!") // probably about time this got fixed - todo change this back when we fix font rendering
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> timestamps = sgGeneral.add(new BoolSetting.Builder()
        .name("timestamps")
        .description("Adds client-side time stamps to the beginning of chat messages.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> playerHeads = sgGeneral.add(new BoolSetting.Builder()
        .name("player-heads")
        .description("Displays player heads next to their messages.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> coordsProtection = sgGeneral.add(new BoolSetting.Builder()
        .name("coords-protection")
        .description("Prevents you from sending messages in chat that may contain coordinates.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> keepHistory = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-history")
        .description("Prevents the chat history from being cleared when disconnecting.")
        .defaultValue(true)
        .build()
    );

    // Filter

    private final Setting<Boolean> antiSpam = sgFilter.add(new BoolSetting.Builder()
        .name("anti-spam")
        .description("Blocks duplicate messages from filling your chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> antiSpamDepth = sgFilter.add(new IntSetting.Builder()
        .name("depth")
        .description("How many messages to filter.")
        .defaultValue(20)
        .min(1).sliderMin(1)
        .sliderMax(40) // arbitrary but twice the default isn't unreasonable
        .visible(antiSpam::get)
        .build()
    );

    private final Setting<Boolean> filterRegex = sgFilter.add(new BoolSetting.Builder()
        .name("filter-regex")
        .description("Filter out chat messages that match the regex filter.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> regexFilters = sgFilter.add(new StringListSetting.Builder()
        .name("regex-filters")
        .description("Regex filters used for filtering chat messages.")
        .visible(filterRegex::get)
        .onChanged(strings -> compileFilterRegexList())
        .build()
    );

    // Longer chat

    private final Setting<Boolean> infiniteChatBox = sgLongerChat.add(new BoolSetting.Builder()
        .name("infinite-chat-box")
        .description("Lets you type infinitely long messages.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> longerChatHistory = sgLongerChat.add(new BoolSetting.Builder()
        .name("longer-chat-history")
        .description("Extends chat length.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> longerChatLines = sgLongerChat.add(new IntSetting.Builder()
        .name("extra-lines")
        .description("The amount of extra chat lines.")
        .defaultValue(1000)
        .min(100)
        .sliderRange(100, 1000)
        .visible(longerChatHistory::get)
        .build()
    );

    // Prefix

    private final Setting<Boolean> prefix = sgPrefix.add(new BoolSetting.Builder()
        .name("prefix")
        .description("Adds a prefix to your chat messages.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> prefixRandom = sgPrefix.add(new BoolSetting.Builder()
        .name("random")
        .description("Uses a random number as your prefix.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> prefixText = sgPrefix.add(new StringSetting.Builder()
        .name("text")
        .description("The text to add as your prefix.")
        .defaultValue("> ")
        .visible(() -> !prefixRandom.get())
        .build()
    );

    private final Setting<Boolean> prefixSmallCaps = sgPrefix.add(new BoolSetting.Builder()
        .name("small-caps")
        .description("Uses small caps in the prefix.")
        .defaultValue(false)
        .visible(() -> !prefixRandom.get())
        .build()
    );

    // Suffix

    private final Setting<Boolean> suffix = sgSuffix.add(new BoolSetting.Builder()
        .name("suffix")
        .description("Adds a suffix to your chat messages.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> suffixRandom = sgSuffix.add(new BoolSetting.Builder()
        .name("random")
        .description("Uses a random number as your suffix.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> suffixText = sgSuffix.add(new StringSetting.Builder()
        .name("text")
        .description("The text to add as your suffix.")
        .defaultValue(" | meteor on crack!")
        .visible(() -> !suffixRandom.get())
        .build()
    );

    private final Setting<Boolean> suffixSmallCaps = sgSuffix.add(new BoolSetting.Builder()
        .name("small-caps")
        .description("Uses small caps in the suffix.")
        .defaultValue(true)
        .visible(() -> !suffixRandom.get())
        .build()
    );

    private static final Pattern antiSpamRegex = Pattern.compile(".*(\\([0-9]+\\)$)");
    private static final Pattern timestampRegex = Pattern.compile("^(<[0-9]{2}:[0-9]{2}>\\s)");

    private final Char2CharMap SMALL_CAPS = new Char2CharOpenHashMap();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm");

    public BetterChat() {
        super(Categories.Misc, "better-chat", "Improves your chat experience in various ways.");

        String[] a = "abcdefghijklmnopqrstuvwxyz".split("");
        String[] b = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴩqʀꜱᴛᴜᴠᴡxyᴢ".split("");
        for (int i = 0; i < a.length; i++) SMALL_CAPS.put(a[i].charAt(0), b[i].charAt(0));
        compileFilterRegexList();
    }

    @EventHandler
    private void onMessageReceive(ReceiveMessageEvent event) {
        Text message = event.getMessage();

        if (filterRegex.get()) {
            for (Pattern pattern : filterRegexList) {
                if (pattern.matcher(message.getString()).find()) {
                    event.cancel();
                    return;
                }
            }
        }

        if (playerHeads.get()) {
            message = Text.literal("  ").append(message);
        }

        if (antiSpam.get()) {
            Text antiSpammed = appendAntiSpam(message);

            if (antiSpammed != null) {
                message = antiSpammed;
            }
        }

        if (timestamps.get()) {
            Matcher matcher = timestampRegex.matcher(message.getString());
            if (matcher.matches()) message.getSiblings().subList(0, 8).clear();

            Text timestamp = Text.literal("<" + dateFormat.format(new Date()) + "> ").formatted(Formatting.GRAY);

            message = Text.literal("").append(timestamp).append(message);
        }

        event.setMessage(message);
    }


    private Text appendAntiSpam(Text text) {
        int index;
        int lines = -1;
        int width = 0;

        ChatHudLine message;
        MutableText messageCopy = null;
        Text returnText = null;

        List<ChatHudLine> messages = ((ChatHudAccessor) mc.inGameHud.getChatHud()).getMessages();
        if (messages.isEmpty()) return null;

        for (index = 0; index < Math.min(antiSpamDepth.get(), messages.size()); index++) {
            message = messages.get(index);
            messageCopy = message.content().copy();

            String messageToCheck = messageCopy.getString();
            String newMessage = text.getString();

            // calculating the number of lines in visibleMessages we need to go back to start removing
            width = MathHelper.floor((double) mc.inGameHud.getChatHud().getWidth() / mc.inGameHud.getChatHud().getChatScale());
            if (message.indicator() != null && message.indicator().icon() != null) {
                width -= message.indicator().icon().width + 4 + 2;
            }

            TextCollector textCollector = new TextCollector();
            messageCopy.visit((style, messagex) -> {
                textCollector.add(StringVisitable.styled(getRenderedChatMessage(messagex), style));
                return Optional.empty();
            }, Style.EMPTY);

            lines += Math.max(getLines(textCollector.getCombined(), width), 1); // if we're calling this at all on a message, it has to be *at least* one line long

            // handling timestamps
            Matcher timestampMatcher = timestampRegex.matcher(messageToCheck);
            if (timestampMatcher.find()) {
                messageToCheck = messageToCheck.substring(8);
            }

            if (messageToCheck.equals(newMessage)) {
                returnText = text.copy().append(Text.literal(" (2)").formatted(Formatting.GRAY));
                break;
            }
            else {
                Matcher matcher = antiSpamRegex.matcher(messageToCheck);
                // 'matches' returns true only if the whole string matches, 'find' returns true only if a subsequence matches - line breaks would cause this to fail otherwise
                if (!matcher.matches() && !matcher.find()) continue;

                String group = matcher.group(matcher.groupCount());
                int number = Integer.parseInt(group.substring(1, group.length() - 1));

                String counter = " (" + number + ")";

                if (messageToCheck.substring(0, messageToCheck.length() - counter.length()).equals(newMessage)) {
                    returnText = text.copy().append(Text.literal(" (" + (number + 1) + ")").formatted(Formatting.GRAY));
                    break;
                }
            }
        }

        if (returnText != null) {
            messages.remove(index);

            List<ChatHudLine.Visible> visibleMessages = ((ChatHudAccessor) mc.inGameHud.getChatHud()).getVisibleMessages();

            for (int i = 0; i < ChatMessages.breakRenderedChatMessageLines(messageCopy, width, mc.textRenderer).size(); i++) {
                visibleMessages.remove(lines - i);
            }
        }

        return returnText;
    }

    private String getRenderedChatMessage(String message) {
        return mc.options.getChatColors().getValue() ? message : Formatting.strip(message);
    }

    private int getLines(StringVisitable text, int width) {
        int lines = 0;

        List<TextHandler.StyledString> textList = Lists.newArrayList();
        text.visit((style, text2) -> {
            if (!text2.isEmpty()) {
                textList.add(new TextHandler.StyledString(text2, style));
            }

            return Optional.empty();
        }, Style.EMPTY);

        TextHandler.LineWrappingCollector lineWrappingCollector = new TextHandler.LineWrappingCollector(textList);

        boolean hasText = true;
        boolean lastCharLineBreak = false;

        while (hasText) {
            hasText = false;
            TextHandler.LineBreakingVisitor lineBreakingVisitor = (mc.textRenderer.getTextHandler()).new LineBreakingVisitor((float) width);

            for (TextHandler.StyledString styledString : lineWrappingCollector.parts) {
                boolean noBreaks = TextVisitFactory.visitFormatted(styledString.literal, 0, styledString.style, Style.EMPTY, lineBreakingVisitor);

                if (!noBreaks) {
                    int endIndex = lineBreakingVisitor.getEndingIndex();
                    char c = lineWrappingCollector.charAt(endIndex);
                    lastCharLineBreak = c == '\n';

                    lineWrappingCollector.collectLine(endIndex, lastCharLineBreak || c == ' ' ? 1 : 0, lineBreakingVisitor.getEndingStyle());

                    lines += 1;
                    hasText = true;
                    break;
                }

                lineBreakingVisitor.offset(styledString.literal.length());
            }
        }

        if (lineWrappingCollector.collectRemainers() != null) {
            lines += 1;
        } else if (lastCharLineBreak) {
            lines += 1;
        }

        return lines;
    }


    @EventHandler
    private void onMessageSend(SendMessageEvent event) {
        String message = event.message;

        if (annoy.get()) message = applyAnnoy(message);

        if (fancy.get()) message = applyFancy(message);

        message = getPrefix() + message + getSuffix();

        if (coordsProtection.get() && containsCoordinates(message)) {
            MutableText warningMessage = Text.literal("It looks like there are coordinates in your message! ");

            MutableText sendButton = getSendButton(message);
            warningMessage.append(sendButton);

            ChatUtils.sendMsg(warningMessage);

            event.cancel();
            return;
        }

        event.message = message;
    }

    // Annoy

    private String applyAnnoy(String message) {
        StringBuilder sb = new StringBuilder(message.length());
        boolean upperCase = true;
        for (int cp : message.codePoints().toArray()) {
            if (upperCase) sb.appendCodePoint(Character.toUpperCase(cp));
            else sb.appendCodePoint(Character.toLowerCase(cp));
            upperCase = !upperCase;
        }
        message = sb.toString();
        return message;
    }

    // Fancy

    private String applyFancy(String message) {
        StringBuilder sb = new StringBuilder();

        for (char ch : message.toCharArray()) {
            sb.append(SMALL_CAPS.getOrDefault(ch, ch));
        }

        return sb.toString();
    }

    // Filter Regex

    private final List<Pattern> filterRegexList = new ArrayList<>();

    private void compileFilterRegexList() {
        filterRegexList.clear();

        for (int i = 0; i < regexFilters.get().size(); i++) {
            try {
                filterRegexList.add(Pattern.compile(regexFilters.get().get(i)));
            } catch (PatternSyntaxException e) {
                String removed = regexFilters.get().remove(i);
                error("Removing invalid regex: %s", removed);
            }
        }
    }

    // Prefix and Suffix

    private String getPrefix() {
        return prefix.get() ? getAffix(prefixText.get(), prefixSmallCaps.get(), prefixRandom.get()) : "";
    }

    private String getSuffix() {
        return suffix.get() ? getAffix(suffixText.get(), suffixSmallCaps.get(), suffixRandom.get()) : "";
    }

    private String getAffix(String text, boolean smallCaps, boolean random) {
        if (random) return String.format("(%03d) ", Utils.random(0, 1000));
        else if (smallCaps) return applyFancy(text);
        else return text;
    }

    // Coords Protection

    private static final Pattern coordRegex = Pattern.compile("(?<x>-?\\d{3,}(?:\\.\\d*)?)(?:\\s+(?<y>-?\\d{1,3}(?:\\.\\d*)?))?\\s+(?<z>-?\\d{3,}(?:\\.\\d*)?)");

    private boolean containsCoordinates(String message) {
        return coordRegex.matcher(message).find();
    }

    private MutableText getSendButton(String message) {
        MutableText sendButton = Text.literal("[SEND ANYWAY]");
        MutableText hintBaseText = Text.literal("");

        MutableText hintMsg = Text.literal("Send your message to the global chat even if there are coordinates:");
        hintMsg.setStyle(hintBaseText.getStyle().withFormatting(Formatting.GRAY));
        hintBaseText.append(hintMsg);

        hintBaseText.append(Text.literal('\n' + message));

        sendButton.setStyle(sendButton.getStyle()
            .withFormatting(Formatting.DARK_RED)
            .withClickEvent(new ClickEvent(
                ClickEvent.Action.RUN_COMMAND,
                Commands.get().get("say").toString(message)
            ))
            .withHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                hintBaseText
            )));
        return sendButton;
    }

    // Longer chat

    public boolean isInfiniteChatBox() { return isActive() && infiniteChatBox.get(); }

    public boolean isLongerChat() { return isActive() && longerChatHistory.get(); }

    public boolean displayPlayerHeads() { return isActive() && playerHeads.get(); }

    public boolean keepHistory() { return isActive() && keepHistory.get(); }

    public int getChatLength() { return longerChatLines.get(); }
}
