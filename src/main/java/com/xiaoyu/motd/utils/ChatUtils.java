package com.xiaoyu.motd.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import com.velocitypowered.api.command.CommandSource;

public class ChatUtils {
    private static final String prefix = "&8[&fXiaoyuMotd&8]&7 ";

    public static void sendMessage(CommandSource sender, String message) {
        String fullMessage = prefix + message;
        sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(fullMessage));
    }
}
