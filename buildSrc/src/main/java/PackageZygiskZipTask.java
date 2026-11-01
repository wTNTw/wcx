import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * 把「APK + Zygisk 注入器」组装成可直接刷入 Magisk / KernelSU / APatch 的模块 zip。
 *
 * <p>产物结构（arm64-v8a）：
 * <pre>
 *   module.prop                      模块信息（版本号由构建注入）
 *   customize.sh                     安装校验 + 权限修正
 *   service.sh / uninstall.sh        开机后置 / 卸载
 *   META-INF/com/google/android/     Magisk 官方安装器
 *   zygisk/arm64-v8a.so              注入器（含 LSPlant hook 桥）
 *   zygisk/liblsplant.so             ART hook 引擎（运行时 dlopen）
 *   payload/classes*.dex             模块代码（不装 APK，直接从内存加载）
 *   lib/arm64-v8a/*.so               模块原生库（zygote 阶段预加载）
 * </pre>
 */
public abstract class PackageZygiskZipTask extends DefaultTask {

    private static final Pattern DEX_PATTERN = Pattern.compile("classes\\d*\\.dex");

    /** APK 产物目录（app/build/outputs/apk/&lt;flavor&gt;/release）。 */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getApkDir();

    /** 构建出的注入器 .so（libwcxzygisk.so）。 */
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getZygiskSo();

    /** 从 lsplant-standalone AAR 里取出的 liblsplant.so。 */
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getLsplantSo();

    /** Magisk 模块模板目录（zygisk/magisk）。 */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getTemplateDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Input
    public abstract org.gradle.api.provider.Property<String> getVersionName();

    @Input
    public abstract org.gradle.api.provider.Property<Integer> getVersionCode();

    @Input
    @Optional
    public abstract org.gradle.api.provider.Property<String> getModuleAuthor();

    @Input
    @Optional
    public abstract org.gradle.api.provider.Property<String> getModuleDescription();

    /** 只接受 arm64-v8a 的 APK（模块只发布这一个 ABI）。 */
    @Input
    @Optional
    public abstract org.gradle.api.provider.Property<String> getAbi();

    @TaskAction
    public void packageZip() throws IOException {
        String abi = getAbi().getOrElse("arm64-v8a");
        File apk = pickApk();
        File stage = new File(getTemporaryDir(), "module");
        deleteRecursively(stage.toPath());
        Files.createDirectories(stage.toPath());

        copyTemplates(stage.toPath());
        renderTokens(stage.toPath(), abi);

        // 注入器 + LSPlant
        Path zygiskDir = stage.toPath().resolve("zygisk");
        Files.createDirectories(zygiskDir);
        Files.copy(getZygiskSo().get().getAsFile().toPath(), zygiskDir.resolve(abi + ".so"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(getLsplantSo().get().getAsFile().toPath(), zygiskDir.resolve("liblsplant.so"),
                StandardCopyOption.REPLACE_EXISTING);

        // 从 APK 里抽 payload（dex + 原生库）
        extractPayload(apk, stage.toPath(), abi);

        Path out = getOutputDir().get().getAsFile().toPath();
        Files.createDirectories(out);
        String versionName = getVersionName().get();
        Path zip = out.resolve("wcx-" + versionName + "-zygisk-" + abi + ".zip");
        Files.deleteIfExists(zip);
        zipDirectory(stage.toPath(), zip);

        getLogger().lifecycle("Zygisk 模块 zip: {} ({} KB)",
                zip.toAbsolutePath(),
                Files.size(zip) / 1024);
    }

    private File pickApk() {
        File dir = getApkDir().get().getAsFile();
        List<File> candidates = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir.toPath())) {
            stream.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(f -> f.getName().endsWith(".apk"))
                    .filter(f -> f.getName().contains(getAbi().getOrElse("arm64-v8a")))
                    .filter(f -> !f.getName().contains("universal"))
                    .forEach(candidates::add);
        } catch (IOException e) {
            throw new GradleException("读取 APK 目录失败: " + dir, e);
        }
        if (candidates.isEmpty()) {
            throw new GradleException("在 " + dir + " 下找不到包含 " + getAbi().getOrElse("arm64-v8a")
                    + " 的 APK（请先 assembleRelease）");
        }
        // 多个候选时取最新产出的那个
        candidates.sort(Comparator.comparingLong(File::lastModified).reversed());
        return candidates.get(0);
    }

    private void copyTemplates(Path stage) throws IOException {
        File template = getTemplateDir().get().getAsFile();
        try (Stream<Path> stream = Files.walk(template.toPath())) {
            for (Path src : stream.filter(Files::isRegularFile).toList()) {
                Path relative = template.toPath().relativize(src);
                Path dst = stage.resolve(relative.toString());
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void renderTokens(Path stage, String abi) throws IOException {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("VERSION_NAME", getVersionName().get());
        tokens.put("VERSION_CODE", String.valueOf(getVersionCode().get()));
        tokens.put("AUTHOR", getModuleAuthor().getOrElse("wcx-dev"));
        tokens.put("DESCRIPTION", getModuleDescription().getOrElse(
                "不安装 APK 的 Zygisk 注入版：由模块自带的注入器把 WCX 注入微信进程。"
                        + "需要开启 Zygisk，无需 LSPosed。"));
        tokens.put("ABI", abi);

        Path propTemplate = stage.resolve("module.prop.in");
        if (Files.exists(propTemplate)) {
            String rendered = render(Files.readString(propTemplate), tokens);
            Files.writeString(stage.resolve("module.prop"), rendered);
            Files.delete(propTemplate);
        } else {
            throw new GradleException("模板目录缺少 module.prop.in: " + getTemplateDir().get());
        }

        // 脚本里只替换白名单 token（${MODPATH} 之类运行时变量必须原样保留）
        for (String script : new String[]{"customize.sh", "service.sh", "uninstall.sh"}) {
            Path file = stage.resolve(script);
            if (!Files.exists(file)) continue;
            String content = Files.readString(file);
            String rendered = render(content, tokens);
            if (!rendered.equals(content)) {
                Files.writeString(file, rendered);
            }
        }
    }

    private static String render(String content, Map<String, String> tokens) {
        String result = content;
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            result = result.replace("${" + e.getKey() + "}", e.getValue());
        }
        return result;
    }

    private void extractPayload(File apk, Path stage, String abi) throws IOException {
        Path payloadDir = stage.resolve("payload");
        Path libDir = stage.resolve("lib").resolve(abi);
        Files.createDirectories(payloadDir);
        Files.createDirectories(libDir);

        String libPrefix = "lib/" + abi + "/";
        int dexCount = 0;
        int libCount = 0;

        try (ZipFile zip = new ZipFile(apk)) {
            List<? extends ZipEntry> entries = zip.stream().collect(Collectors.toList());
            for (ZipEntry entry : entries) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (DEX_PATTERN.matcher(name).matches()) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, payloadDir.resolve(name),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                    dexCount++;
                } else if (name.startsWith(libPrefix) && name.endsWith(".so")) {
                    String fileName = name.substring(libPrefix.length());
                    if (fileName.contains("/")) continue;
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, libDir.resolve(fileName),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                    libCount++;
                }
            }
        }

        if (dexCount == 0) {
            throw new GradleException("APK 内没有 classes*.dex: " + apk);
        }
        getLogger().lifecycle("payload: {} 个 dex, {} 个原生库", dexCount, libCount);
    }

    private static void zipDirectory(Path source, Path target) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            out.setLevel(9);
            List<Path> files;
            try (Stream<Path> stream = Files.walk(source)) {
                files = stream.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : files) {
                String name = source.relativize(file).toString().replace(File.separatorChar, '/');
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(0L); // 可复现：固定时间戳
                out.putNextEntry(entry);
                try (InputStream in = Files.newInputStream(file)) {
                    in.transferTo(out);
                }
                out.closeEntry();
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
