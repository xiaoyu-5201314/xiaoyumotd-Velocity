package com.xiaoyu.motd;

import com.google.inject.Inject;
import com.xiaoyu.motd.commands.MainCommand;
import com.xiaoyu.motd.utils.CacheObf;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import net.kyori.adventure.text.format.ShadowColor;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Plugin(
        id = "xiaoyumotd",
        name = "XiaoyuMotd",
        version = "1.0.0",
        authors = {"xiaoyu"}
)
public class XiaoyuMotd {
    private final List<boolean[]> activeUploadTasks = new CopyOnWriteArrayList<>();

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private ConfigurationNode config;
    private Component currentMotd = Component.empty();

    @Inject
    public XiaoyuMotd(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        saveDefaultConfig();
        loadConfig();

        CommandManager commandManager = server.getCommandManager();
        MainCommand mainCommand = new MainCommand(this);
        CommandMeta meta = commandManager.metaBuilder("xiaoyumotd")
                .plugin(this)
                .build();
        commandManager.register(meta, mainCommand);

        try {
            Path imagesDir = dataDirectory.resolve("images");
            Path cacheDir = dataDirectory.resolve("cache");
            Files.createDirectories(imagesDir);
            Files.createDirectories(cacheDir);
            copyDefaultFile("MOTD.png", imagesDir.resolve("MOTD.png"));
            copyDefaultFile("MOTD.cache", cacheDir.resolve("MOTD.cache"));
        } catch (IOException e) {
            logger.error("创建文件夹失败: " + e.getMessage());
        }
        loadMotd();
        logger.info("Author: xiaoyu");
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        if (currentMotd != Component.empty()) {
            ServerPing ping = event.getPing();
            event.setPing(ping.asBuilder()
                    .description(currentMotd)
                    .build());
        }
    }

    private void saveDefaultConfig() {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }
            Path configFile = dataDirectory.resolve("config.yml");
            if (!Files.exists(configFile)) {
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("保存默认配置失败: " + e.getMessage());
        }
    }

    public void loadConfig() {
        try {
            Path configFile = dataDirectory.resolve("config.yml");
            YAMLConfigurationLoader loader = YAMLConfigurationLoader.builder().setPath(configFile).build();
            config = loader.load();
        } catch (IOException e) {
            logger.error("加载配置失败: " + e.getMessage());
        }
    }

    private void copyDefaultFile(String resourcePath, Path targetPath) {
        try {
            if (Files.exists(targetPath)) {
                return;
            }
            try (InputStream inputStream = getClass().getResourceAsStream("/" + resourcePath)) {
                if (inputStream == null) {
                    logger.warn("未找到默认资源文件: " + resourcePath);
                    return;
                }
                Files.copy(inputStream, targetPath);
                logger.info("创建默认文件: " + targetPath.getFileName());
            }
        } catch (IOException e) {
            logger.warn("创建默认文件失败 " + resourcePath + " 错误: " + e.getMessage());
        }
    }

    public void onDisable() {
        if (!activeUploadTasks.isEmpty()) {
            logger.info("正在取消 " + activeUploadTasks.size() + " 个图片上传任务。");
            for (boolean[] cancelFlag : activeUploadTasks) {
                cancelFlag[0] = true;
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            activeUploadTasks.clear();
        }
    }

    public void registerUploadTask(boolean[] cancelFlag) {
        activeUploadTasks.add(cancelFlag);
    }

    public void unregisterUploadTask(boolean[] cancelFlag) {
        activeUploadTasks.remove(cancelFlag);
    }

    public void reloadMotd() {
        loadConfig();
        loadMotd();
    }

    private void loadMotd() {
        String imageFileTxt = config.getNode("image-motd", "image").getString();
        if (imageFileTxt == null || imageFileTxt.isBlank()) {
            return;
        }
        Path cacheFilePath = dataDirectory.resolve("cache").resolve(imageFileTxt);
        List<String> textureSources = loadTextureSources(cacheFilePath);
        if (textureSources.isEmpty()) {
            logger.warn("未加载缓存，请先使用插件命令创建图片缓存。");
            return;
        }
        List<String> unsignedTextureValues = textureSources.stream().map(this::toUnsignedTextureValue).toList();
        currentMotd = buildUnsignedHeadMotd(unsignedTextureValues).color(NamedTextColor.WHITE).shadowColor(ShadowColor.shadowColor(-1));
    }

    private List<String> loadTextureSources(Path cacheFilePath) {
        if (!Files.exists(cacheFilePath)) {
            logger.warn("未找到缓存文件: " + cacheFilePath.getFileName());
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(cacheFilePath, StandardCharsets.UTF_8);
            boolean isObfuscated = !lines.isEmpty() && CacheObf.isObfuscated(lines.get(0));
            if (isObfuscated) {
                return lines.stream().map(CacheObf::deobfuscate).map(String::trim).filter(line -> !line.isEmpty()).toList();
            }
            return lines.stream().map(String::trim).filter(line -> !line.isEmpty()).toList();
        } catch (IOException ex) {
            logger.error("缓存文件读取失败: " + cacheFilePath, ex);
            return List.of();
        }
    }

    private String toUnsignedTextureValue(String textureSource) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + textureSource + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private Component buildUnsignedHeadMotd(List<String> unsignedTextureValues) {
        if (unsignedTextureValues.isEmpty()) {
            return Component.empty();
        }

        Component motd = Component.empty();
        int totalHeads = unsignedTextureValues.size();
        int addedHeads = 0;
        
        // Minecraft MOTD 每行限制，约 33 颗头颗（8x8 像素）可以铺满一行
        int headsPerLine = 33;
        // Velocity/Minecraft MOTD 通常只显示 2 行
        int maxRows = 2;
        // 组件序列化后的字节数限制，通常为 32767 字节 (Short.MAX_VALUE)
        int maxJsonLength = 32000;

        int actualRows = (int) Math.ceil((double) totalHeads / (double) headsPerLine);
        actualRows = Math.min(actualRows, maxRows);

        for (int row = 0; row < actualRows; ++row) {
            if (row > 0) {
                Component withNewline = motd.appendNewline();
                if (GsonComponentSerializer.gson().serialize(withNewline).length() > maxJsonLength) break;
                motd = withNewline;
            }
            for (int col = 0; col < headsPerLine && addedHeads < totalHeads; ++addedHeads, ++col) {
                String textureValue = unsignedTextureValues.get(addedHeads);
                try {
                    PlayerHeadObjectContents contents = ObjectContents.playerHead()
                            .profileProperty(PlayerHeadObjectContents.property("textures", textureValue))
                            .hat(true)
                            .build();
                    Component head = Component.object(contents);
                    Component candidate = motd.append(head);
                    // 检查序列化后的长度，避免超过 Minecraft 协议限制
                    if (GsonComponentSerializer.gson().serialize(candidate).length() > maxJsonLength) {
                        row = actualRows;
                        break;
                    }
                    motd = candidate;
                } catch (Exception e) {
                    logger.error("渲染第 " + (addedHeads + 1) + " 个头像时出错，使用占位符代替", e);
                    motd = motd.append(Component.text("■", NamedTextColor.GRAY));
                }
            }
            if (addedHeads >= totalHeads) break;
        }

        logger.info("图片加载成功: " + addedHeads + "/" + totalHeads + " 头颗 " + actualRows + " 行。");
        return motd;
    }

    public ProxyServer getServer() {
        return server;
    }

    public Logger getLogger() {
        return logger;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public ConfigurationNode getConfig() {
        return config;
    }
}
