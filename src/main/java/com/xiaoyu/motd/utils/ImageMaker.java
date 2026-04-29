package com.xiaoyu.motd.utils;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import javax.imageio.ImageIO;

public class ImageMaker {
    private static final int TILE_SIZE = 8;
    private static final int OUTPUT_SIZE = 64;
    private static final int HEAD_X = 8;
    private static final int HEAD_Y = 8;

    public static MakeSkinsResult makeSkins(MakeSkinsOptions options) throws IOException {
        String inputPath = options.inputPath() != null ? options.inputPath() : "MOTD.png";
        String outputDir = options.outputDir() != null ? options.outputDir() : "tmp";
        boolean cleanOutputDir = options.cleanOutputDir();
        Path outputPath = Paths.get(outputDir);
        if (cleanOutputDir && Files.exists(outputPath)) {
            deleteDirectory(outputPath.toFile());
        }
        Files.createDirectories(outputPath);
        File sourceFile = new File(inputPath);
        BufferedImage source = ImageIO.read(sourceFile);
        if (source == null) {
            throw new IOException("无法从 " + inputPath + " 读取图片。");
        }
        int width = source.getWidth();
        int height = source.getHeight();
        int columns = width / 8;
        int rows = height / 8;
        if (columns == 0 || rows == 0) {
            throw new IllegalArgumentException(inputPath + " 的图片规格小于 8x8");
        }
        int tileId = 0;
        for (int row = 0; row < rows; ++row) {
            for (int column = 0; column < columns; ++column) {
                BufferedImage tile = source.getSubimage(column * 8, row * 8, 8, 8);
                BufferedImage output = new BufferedImage(64, 64, 2);
                for (int y = 0; y < 8; ++y) {
                    for (int x = 0; x < 8; ++x) {
                        output.setRGB(8 + x, 8 + y, tile.getRGB(x, y));
                    }
                }
                File outputFile = new File(outputDir, "skin_" + tileId + ".png");
                ImageIO.write((RenderedImage)output, "PNG", outputFile);
                ++tileId;
            }
        }
        return new MakeSkinsResult(outputDir, tileId, columns, rows);
    }

    private static void deleteDirectory(File directory) {
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

    public record MakeSkinsOptions(String inputPath, String outputDir, boolean cleanOutputDir) {
    }

    public record MakeSkinsResult(String outputDir, int generatedCount, int columns, int rows) {
    }
}

