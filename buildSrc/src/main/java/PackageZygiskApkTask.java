import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

/**
 * 把 release APK 变成「APK + Magisk/KernelSU 模块」一体包。
 *
 * <p>核心限制：APK 的 v2/v3 签名覆盖整个 zip（条目 + 中央目录），所以模块文件必须
 * <b>在签名之前</b>写进 APK，签名之后再改就会装不上。因此流程固定为：
 *
 * <ol>
 *   <li>把 APK 重新打包，注入模块条目（module.prop / customize.sh / zygisk/*.so …），
 *       丢弃旧签名（MANIFEST.MF / *.SF / *.RSA）</li>
 *   <li>{@code zipalign -P 16 -p 4}：{@code extractNativeLibs=false} 要求 .so 未压缩且按页对齐，
 *       重打包后必须重新对齐，否则微信/系统加载原生库会失败</li>
 *   <li>{@code apksigner} 用仓库固定密钥重签（CI 里 WEKIT_KEYSTORE_* 已导出；
 *       本地没有 keystore 时产物为 unsigned，仅用于测试）</li>
 * </ol>
 *
 * <p>产物同时可当模块包用：把 {@code .apk} 改名成 {@code .zip} 直接刷入。
 * Magisk/KernelSU 的安装器只解压除 {@code META-INF/*} 之外的内容，
 * 于是 APK 里的 {@code classes*.dex}、{@code lib/<abi>/*.so} 会原样落到模块目录，
 * 正好就是注入器需要的 payload —— 不需要重复打包。
 */
public abstract class PackageZygiskApkTask extends DefaultTask {

    @Inject
    protected abstract ExecOperations getExecOperations();

    /** APK 产物目录（app/build/outputs/apk/&lt;flavor&gt;/release）。 */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getApkDir();

    /** Magisk 模块模板目录（zygisk/magisk）。 */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getTemplateDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Input
    public abstract Property<String> getVersionName();

    @Input
    public abstract Property<Integer> getVersionCode();

    @Input
    @Optional
    public abstract Property<String> getModuleAuthor();

    @Input
    @Optional
    public abstract Property<String> getModuleDescription();

    @Input
    @Optional
    public abstract Property<String> getAbi();

    /** Android SDK 根目录，用来找 build-tools 里的 zipalign / apksigner。 */
    @Input
    @Optional
    public abstract Property<String> getSdkDirectory();

    /** 根工程目录（用于兜底找 local.properties 里的 sdk.dir 与 keystore）。 */
    @Input
    @Optional
    public abstract Property<String> getRootProjectDir();

    @TaskAction
    public void packageUnifiedApk() throws IOException {
        String abi = getAbi().getOrElse("arm64-v8a");
        File apk = pickApk(abi);
        Path stage = getTemporaryDir().toPath().resolve("unified");
        deleteRecursively(stage);
        Files.createDirectories(stage);

        Path injected = stage.resolve("injected.apk");
        injectModuleEntries(apk, injected);

        Path aligned = stage.resolve("aligned.apk");
        File zipalign = findBuildTool("zipalign");
        // -P 16：把未压缩的 .so 按 16KB 页对齐（extractNativeLibs=false + 16KB 页设备必需）；
        // 注意 -P 与 -p 互斥
        runTool(List.of(zipalign.getAbsolutePath(), "-P", "16", "-f", "4",
                injected.toString(), aligned.toString()));

        SigningConfig signing = resolveSigning();
        Path outDir = getOutputDir().get().getAsFile().toPath();
        Files.createDirectories(outDir);
        // 清掉上一次的产物，避免旧的独立 zip / 旧版本 APK 混在产物目录里
        try (var existing = Files.list(outDir)) {
            for (Path stale : existing.toList()) {
                String name = stale.getFileName().toString();
                if (name.startsWith("wcx-") && (name.endsWith(".apk") || name.endsWith(".zip"))) {
                    Files.deleteIfExists(stale);
                }
            }
        }
        String baseName = "wcx-" + getVersionName().get() + "-" + abi;
        Path finalApk = outDir.resolve(baseName + ".apk");

        if (signing != null) {
            File apksigner = findBuildTool("apksigner");
            List<String> cmd = new ArrayList<>(List.of(
                    apksigner.getAbsolutePath(), "sign",
                    "--ks", signing.keystore,
                    "--ks-pass", "pass:" + signing.storePassword,
                    "--ks-key-alias", signing.keyAlias,
                    "--key-pass", "pass:" + signing.keyPassword,
                    "--v1-signing-enabled", "true",
                    "--v2-signing-enabled", "true",
                    "--v3-signing-enabled", "true",
                    "--out", finalApk.toString(),
                    aligned.toString()));
            runTool(cmd);
            getLogger().lifecycle("已用 {} 重签", signing.keystore);
        } else {
            getLogger().warn("未找到固定 keystore，产物未签名（仅可用于本机测试，无法覆盖安装）");
            Files.copy(aligned, finalApk, StandardCopyOption.REPLACE_EXISTING);
        }

        // 同一个文件再存一份 .zip，方便直接刷入（内容完全相同）
        Path finalZip = outDir.resolve(baseName + ".zip");
        Files.copy(finalApk, finalZip, StandardCopyOption.REPLACE_EXISTING);

        getLogger().lifecycle("一体包: {} ({} MB，改名为 .zip 即可刷入)",
                finalApk.toAbsolutePath(), Files.size(finalApk) / 1024 / 1024);
    }

    // ── APK 注入 ────────────────────────────────────────────────────────────

    private void injectModuleEntries(File apk, Path target) throws IOException {
        // 注入模块元数据与安装脚本；注入器本体作为 lib/<abi>/libwcx_zygisk.so 随 APK 打包，
        // 由 customize.sh 在安装时搬到 $MODPATH/zygisk/<abi>.so。
        Map<String, byte[]> extra = new LinkedHashMap<>(renderTemplates(getAbi().getOrElse("arm64-v8a")));

        try (ZipFile src = new ZipFile(apk);
             ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(
                     Files.newOutputStream(target)))) {
            out.setLevel(6);
            Enumeration<? extends ZipEntry> entries = src.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (isOldSignature(entry.getName())) {
                    continue; // 旧签名由 apksigner 重新生成
                }
                copyEntry(src, entry, out);
            }
            for (Map.Entry<String, byte[]> e : extra.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(0L);
                out.putNextEntry(entry);
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        getLogger().lifecycle("已注入模块条目: {} 个", extra.size());
    }

    private static boolean isOldSignature(String name) {
        return name.startsWith("META-INF/")
                && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA")
                || name.endsWith("MANIFEST.MF"));
    }

    private static void copyEntry(ZipFile src, ZipEntry entry, ZipOutputStream out)
            throws IOException {
        ZipEntry copy = new ZipEntry(entry.getName());
        // 保留压缩方式：STORED（resources.arsc / lib**.so）不能在重打包时被压缩，
        // 否则 extractNativeLibs=false 与 resources.arsc 的 mmap 直读都会失效
        copy.setMethod(entry.getMethod());
        if (entry.getMethod() == ZipEntry.STORED) {
            copy.setSize(entry.getSize());
            copy.setCompressedSize(entry.getSize());
            copy.setCrc(entry.getCrc());
        }
        out.putNextEntry(copy);
        try (InputStream in = src.getInputStream(entry)) {
            in.transferTo(out);
        }
        out.closeEntry();
    }

    private Map<String, byte[]> renderTemplates(String abi) throws IOException {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("VERSION_NAME", getVersionName().get());
        tokens.put("VERSION_CODE", String.valueOf(getVersionCode().get()));
        tokens.put("AUTHOR", getModuleAuthor().getOrElse("wcx-dev"));
        tokens.put("DESCRIPTION", getModuleDescription().getOrElse(
                "不安装 APK 的 Zygisk 注入版：模块自带注入器把 WCX 注入微信进程，"
                        + "需要开启 Zygisk，无需 LSPosed。"));
        tokens.put("ABI", abi);

        // 只注入这些固定条目（模板目录里还有 Rust 源码与文档，不能整目录打进去）
        String[] entries = {
                "module.prop",
                "customize.sh",
                "uninstall.sh",
                "config.sh",
                "action.sh",
                "webroot/index.html",
                "webroot/css/app.css",
                "webroot/js/bridge.js",
                "webroot/js/app.js",
                "sepolicy.rule",
                "META-INF/com/google/android/update-binary",
                "META-INF/com/google/android/updater-script",
        };

        File templateDir = getTemplateDir().get().getAsFile();
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String entry : entries) {
            File file = new File(templateDir, entry);
            if (!file.isFile()) {
                throw new GradleException("模板目录缺少 " + entry + ": " + templateDir);
            }
            result.put(entry, render(Files.readString(file.toPath()), tokens)
                    .getBytes(StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String render(String content, Map<String, String> tokens) {
        String result = content;
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            result = result.replace("${" + e.getKey() + "}", e.getValue());
        }
        return result;
    }

    // ── 工具链 / 签名 ───────────────────────────────────────────────────────

    private File findBuildTool(String name) {
        String sdk = resolveSdkDir();
        if (sdk.isEmpty()) {
            throw new GradleException("找不到 Android SDK（设置 ANDROID_HOME 或 local.properties 的 sdk.dir）");
        }
        File buildTools = new File(sdk, "build-tools");
        File[] versions = buildTools.listFiles(File::isDirectory);
        if (versions == null || versions.length == 0) {
            throw new GradleException("SDK 里没有 build-tools: " + buildTools);
        }
        // 版本号按字符串倒序取最新的一个（36.0.0 > 9.0.0 这类边界不影响）
        List<File> sorted = new ArrayList<>(List.of(versions));
        sorted.sort(Comparator.comparing(File::getName).reversed());
        for (File dir : sorted) {
            File tool = new File(dir, name);
            if (tool.canExecute()) return tool;
        }
        throw new GradleException("build-tools 里找不到 " + name);
    }

    private String resolveSdkDir() {
        if (getSdkDirectory().isPresent()) return getSdkDirectory().get();
        String env = env("ANDROID_HOME");
        if (env == null) env = env("ANDROID_SDK_ROOT");
        if (env != null) return env;
        // Gradle 9 不允许在任务执行期访问 Project，这里直接读根目录下的 local.properties
        if (getRootProjectDir().isPresent()) {
            Path props = Path.of(getRootProjectDir().get(), "local.properties");
            if (Files.exists(props)) {
                try {
                    for (String line : Files.readAllLines(props)) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("sdk.dir=")) {
                            return trimmed.substring("sdk.dir=".length()).trim();
                        }
                    }
                } catch (IOException ignored) {
                    // 忽略，交给下面的报错
                }
            }
        }
        return "";
    }

    private void runTool(List<String> command) {
        getExecOperations().exec(spec -> {
            spec.setCommandLine(command);
            spec.setIgnoreExitValue(false);
        });
    }

    private static final class SigningConfig {
        String keystore;
        String storePassword;
        String keyAlias;
        String keyPassword;
    }

    private SigningConfig resolveSigning() {
        String keystore = env("WEKIT_KEYSTORE_FILE");
        if (keystore == null) {
            String home = System.getProperty("user.home", "");
            for (String candidate : List.of(
                    home + "/wcx-keystore.jks",
                    home + "/.wcx/wcx-keystore.jks",
                    getRootProjectDir().getOrElse(System.getProperty("user.dir", "."))
                            + "/wcx-keystore.jks")) {
                if (new File(candidate).isFile()) {
                    keystore = candidate;
                    break;
                }
            }
        }
        if (keystore == null || !new File(keystore).isFile()) {
            return null;
        }
        SigningConfig config = new SigningConfig();
        config.keystore = keystore;
        config.storePassword = envOr("WEKIT_KEYSTORE_PASSWORD", "wcx-store-pass");
        config.keyAlias = envOr("WEKIT_KEY_ALIAS", "wcx");
        config.keyPassword = envOr("WEKIT_KEY_PASSWORD", config.storePassword);
        return config;
    }

    private static String env(String key) {
        String value = System.getenv(key);
        return value == null || value.isEmpty() ? null : value;
    }

    private static String envOr(String key, String fallback) {
        String value = env(key);
        return value == null ? fallback : value;
    }

    // ── 杂项 ───────────────────────────────────────────────────────────────

    private File pickApk(String abi) {
        File dir = getApkDir().get().getAsFile();
        List<File> candidates = new ArrayList<>();
        try (var stream = Files.list(dir.toPath())) {
            stream.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(f -> f.getName().endsWith(".apk"))
                    .filter(f -> f.getName().contains(abi))
                    .filter(f -> !f.getName().contains("universal"))
                    .forEach(candidates::add);
        } catch (IOException e) {
            throw new GradleException("读取 APK 目录失败: " + dir, e);
        }
        if (candidates.isEmpty()) {
            throw new GradleException("在 " + dir + " 下找不到包含 " + abi + " 的 APK（请先 assembleRelease）");
        }
        candidates.sort(Comparator.comparingLong(File::lastModified).reversed());
        return candidates.get(0);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
