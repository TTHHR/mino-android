package cn.yaasoosoft.mino;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ConfigRepository {
    private static final String CONFIG_DIR = "configs";

    private final Context context;

    public ConfigRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public File getConfigDir() {
        File dir = new File(context.getFilesDir(), CONFIG_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public List<File> listConfigs() {
        File[] files = getConfigDir().listFiles((dir, name) -> name.endsWith(".yml") || name.endsWith(".yaml"));
        if (files == null) {
            return new ArrayList<>();
        }
        Arrays.sort(files, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        return new ArrayList<>(Arrays.asList(files));
    }

    public File importConfig(Uri uri, String originalName) throws IOException {
        String fileName = sanitizeFileName(originalName);
        if (!fileName.endsWith(".yml") && !fileName.endsWith(".yaml")) {
            fileName = fileName + ".yml";
        }

        File target = uniqueFile(fileName);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IOException("无法读取配置文件");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }

        try (InputStream in = new FileInputStream(target)) {
            MinoConfig.load(in);
        }
        return target;
    }

    public MinoConfig readConfig(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return MinoConfig.load(in);
        }
    }

    private File uniqueFile(String fileName) {
        File base = new File(getConfigDir(), fileName);
        if (!base.exists()) {
            return base;
        }
        int dot = fileName.lastIndexOf('.');
        String prefix = dot > 0 ? fileName.substring(0, dot) : fileName;
        String suffix = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File candidate = new File(getConfigDir(), prefix + "-" + i + suffix);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return base;
    }

    private String sanitizeFileName(String originalName) {
        String fallback = "mino.yml";
        if (originalName == null || originalName.trim().isEmpty()) {
            return fallback;
        }
        return originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
