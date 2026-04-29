package com.xiaoyu.motd.commands;

import com.xiaoyu.motd.XiaoyuMotd;
import com.xiaoyu.motd.skin.SkinUploader;
import com.xiaoyu.motd.utils.CacheObf;
import com.xiaoyu.motd.utils.ChatUtils;
import com.xiaoyu.motd.utils.ImageMaker;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public record MainCommand(XiaoyuMotd plugin) implements SimpleCommand {

    @Override
    public void execute(Invocation invocation) {
        CommandSource sender = invocation.source();
        String[] args = invocation.arguments();

        if (!sender.hasPermission("xiaoyumotd.admin")) {
            return;
        }

        if (args.length < 1) {
            ChatUtils.sendMessage(sender, "正确命令: &f/xiaoyumotd <reload/upload> [image.png]");
            return;
        }

        String subCommand = args[0].toLowerCase();
        if (subCommand.equals("reload")) {
            handleReload(sender);
            return;
        }

        if (subCommand.equals("upload")) {
            if (args.length < 2) {
                ChatUtils.sendMessage(sender, "正确命令: &f/xiaoyumotd upload <image.png>");
                return;
            }
            String imageName = args[1];
            plugin.getServer().getScheduler().buildTask(plugin, () -> {
                try {
                    processImageUpload(sender, imageName);
                } catch (Exception e) {
                    ChatUtils.sendMessage(sender, "处理图片时发生错误: &f" + e.getMessage());
                    plugin.getLogger().error("处理图片时发生错误", e);
                }
            }).schedule();
            return;
        }

        ChatUtils.sendMessage(sender, "正确命令: &f/xiaoyumotd <reload/upload> [image.png]");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            return List.of("reload", "upload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("upload")) {
            String input = args[1].toLowerCase();
            List<String> files = new ArrayList<>();
            try {
                Path imagesDir = plugin.getDataDirectory().resolve("images");
                if (Files.exists(imagesDir)) {
                    File[] imageFiles = imagesDir.toFile().listFiles();
                    if (imageFiles != null) {
                        for (File file : imageFiles) {
                            if (file.isFile()) {
                                String fileName = file.getName();
                                if (fileName.toLowerCase().startsWith(input)) {
                                    files.add(fileName);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            return files;
        }
        return List.of();
    }

    private void processImageUpload(CommandSource sender, String imageName) throws IOException, InterruptedException {
        Path imagesDir = plugin.getDataDirectory().resolve("images");
        Path imagePath = imagesDir.resolve(imageName);
        if (!Files.exists(imagePath)) {
            ChatUtils.sendMessage(sender, "图片文件不存在: &f" + imageName);
            return;
        }
        Path cacheDir = plugin.getDataDirectory().resolve("cache");
        Files.createDirectories(cacheDir);
        String cacheFileName = imageName.replaceFirst("\\.[^.]+$", ".cache");
        Path cacheFilePath = cacheDir.resolve(cacheFileName);
        if (Files.exists(cacheFilePath)) {
            List<String> cachedUrls = Files.readAllLines(cacheFilePath, StandardCharsets.UTF_8).stream()
                    .map(CacheObf::deobfuscate)
                    .filter(line -> !line.isEmpty())
                    .toList();
            ChatUtils.sendMessage(sender, "找到缓存文件，包含 &f" + cachedUrls.size() + "&7 个头颗贴图。");
            ChatUtils.sendMessage(sender, "如需重新上传，请删除缓存文件: &f" + cacheFileName);
            return;
        }
        ChatUtils.sendMessage(sender, "开始处理图片: &f" + imageName);
        Path tmpDir = plugin.getDataDirectory().resolve("tmp");
        Files.createDirectories(tmpDir);
        ImageMaker.MakeSkinsResult generated = ImageMaker.makeSkins(new ImageMaker.MakeSkinsOptions(imagePath.toString(), tmpDir.toString(), true));
        ChatUtils.sendMessage(sender, "成功生成 &f" + generated.generatedCount() + "&7 个皮肤文件。");
        
        String apiKey = plugin.getConfig().getNode("api-key").getString();
        if (apiKey == null || apiKey.isBlank()) {
            ChatUtils.sendMessage(sender, "请在&f config.yml &7中配置密钥后再继续上传。");
            return;
        }
        ChatUtils.sendMessage(sender, "正在上传皮肤，进度可在控制台内查看。");
        Path tempResultPath = tmpDir.resolve("upload_result.txt");
        SkinUploader.ProgressCallback progressCallback = message -> plugin.getLogger().info(message);
        boolean[] cancelFlag = new boolean[]{false};
        plugin.registerUploadTask(cancelFlag);
        try {
            SkinUploader.UploadSkinsResult upload = SkinUploader.uploadSkins(new SkinUploader.UploadSkinsOptions(tmpDir.toString(), tempResultPath.toString()), apiKey, progressCallback, cancelFlag);
            if (upload.cancelled()) {
                ChatUtils.sendMessage(sender, "上传任务已被取消。");
                return;
            }
            ChatUtils.sendMessage(sender, "成功上传 &f" + upload.successfulCount() + "&7/&f" + upload.submittedCount() + "&7 个皮肤文件。");
            if (Files.exists(tempResultPath)) {
                List<String> urls = Files.readAllLines(tempResultPath, StandardCharsets.UTF_8);
                if (!urls.isEmpty()) {
                    List<String> obfuscatedUrls = urls.stream().map(CacheObf::obfuscate).collect(Collectors.toList());
                    Files.write(cacheFilePath, obfuscatedUrls, StandardCharsets.UTF_8);
                    ChatUtils.sendMessage(sender, "缓存已保存: &f" + cacheFileName);
                }
                deleteDirectory(tmpDir.toFile());
                ChatUtils.sendMessage(sender, "上传结束，图片全部处理完成。");
            }
        } finally {
            plugin.unregisterUploadTask(cancelFlag);
        }
    }

    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                        continue;
                    }
                    file.delete();
                }
            }
            directory.delete();
        }
    }

    private void handleReload(CommandSource sender) {
        ChatUtils.sendMessage(sender, "正在重新加载插件...");
        try {
            plugin.reloadMotd();
            ChatUtils.sendMessage(sender, "插件重新加载成功。");
        } catch (Exception e) {
            ChatUtils.sendMessage(sender, "插件重新加载失败: &f" + e.getMessage());
        }
    }
}
