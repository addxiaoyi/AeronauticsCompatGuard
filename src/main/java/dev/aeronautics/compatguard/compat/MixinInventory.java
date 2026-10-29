package dev.aeronautics.compatguard.compat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Inventories the mixins other installed mods ship, so overlaps with this mod's targets are visible
 * at runtime instead of requiring an offline jar analysis.
 *
 * <p>Mixin does not expose "who else injects into this method" through its API, but the inputs are
 * all on disk: every mod jar lists its mixin configs, each config lists its mixin classes, and the
 * {@code @Mixin}/{@code @Inject}/{@code @ModifyReturnValue} annotations are runtime-retained. This
 * class reads exactly that, bounded and on a background thread, and answers the one question that
 * matters when patches collide: <em>which mod hits my target method, and does it run before me?</em></p>
 */
public final class MixinInventory {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson JSON = new Gson();
    private static final int MAX_CLASSES = 20_000;

    public record ForeignMixin(
            String modId,
            String mixinClass,
            List<String> targetClasses,
            int priority,
            List<String> injections
    ) {}

    private static final Map<String, ForeignMixin> FOUND = new ConcurrentHashMap<>();
    private static volatile boolean scanning;
    private static volatile boolean complete;
    private static volatile String failure;
    private static volatile int scannedJars;
    private static volatile int scannedClasses;

    private MixinInventory() {}

    public static void startScan() {
        if (scanning || complete) {
            return;
        }
        scanning = true;
        Thread worker = new Thread(MixinInventory::scan, "CompatGuard-MixinInventory");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static void scan() {
        long startedAt = System.nanoTime();
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            for (IModInfo info : ModList.get().getMods()) {
                if ("aeronauticscompatguard".equals(info.getModId())) {
                    continue;
                }
                Path jarPath;
                try {
                    jarPath = info.getOwningFile().getFile().getFilePath();
                } catch (RuntimeException | LinkageError unavailable) {
                    continue;
                }
                if (jarPath == null || !Files.isRegularFile(jarPath)) {
                    continue;
                }
                scannedJars++;
                try (JarFile jar = new JarFile(jarPath.toFile())) {
                    Enumeration<JarEntry> entries = jar.entries();
                    List<String> configs = new ArrayList<>();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.endsWith(".mixins.json") || name.endsWith(".mixin.json")) {
                            configs.add(name);
                        }
                    }
                    for (String configName : configs) {
                        readConfig(info.getModId(), jar, configName, loader);
                        if (scannedClasses >= MAX_CLASSES) {
                            failure = "stopped at " + MAX_CLASSES + " mixin classes";
                            return;
                        }
                    }
                } catch (Exception unreadable) {
                    LOGGER.debug("[CompatGuard] Could not inventory {}", jarPath, unreadable);
                }
            }
            scanClasspath(loader);
        } catch (RuntimeException | LinkageError error) {
            failure = error.toString();
            LOGGER.warn("[CompatGuard] Mixin inventory failed; conflict report will be incomplete", error);
        } finally {
            complete = true;
            scanning = false;
            long millis = (System.nanoTime() - startedAt) / 1_000_000L;
            LOGGER.info("[CompatGuard] Mixin inventory: {} jars, {} mixin classes, {} relevant to our targets, {} ms",
                    scannedJars, scannedClasses, FOUND.size(), millis);
            for (String line : conflictReport()) {
                LOGGER.info("[CompatGuard] {}", line);
            }
        }
    }

    /**
     * In a development environment most mods are classpath directories rather than jars, and the
     * loader reports few owning files. Walking the classpath catches those, plus any jar the loader
     * does not attribute to a mod.
     */
    private static void scanClasspath(ClassLoader loader) {
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.isBlank()) {
            return;
        }
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (entry.isBlank() || scannedClasses >= MAX_CLASSES) {
                continue;
            }
            Path path = Path.of(entry);
            if (Files.isRegularFile(path) && entry.endsWith(".jar")) {
                try (JarFile jar = new JarFile(path.toFile())) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.endsWith(".mixins.json") || name.endsWith(".mixin.json")) {
                            readConfig("classpath:" + path.getFileName(), jar, name, loader);
                        }
                    }
                } catch (Exception unreadable) {
                    LOGGER.debug("[CompatGuard] Could not inventory classpath jar {}", path, unreadable);
                }
                continue;
            }
            if (!Files.isDirectory(path)) {
                continue;
            }
            try (var walk = Files.walk(path, 6)) {
                for (Path candidate : walk.toList()) {
                    String name = candidate.getFileName().toString();
                    if (name.endsWith(".mixins.json") || name.endsWith(".mixin.json")) {
                        try {
                            JsonObject config = JSON.fromJson(Files.readString(candidate), JsonObject.class);
                            readConfigFromJson("classpath:" + name, config, loader);
                        } catch (Exception malformed) {
                            LOGGER.debug("[CompatGuard] Could not read {}", candidate, malformed);
                        }
                    }
                }
            } catch (Exception unreadable) {
                LOGGER.debug("[CompatGuard] Could not walk classpath directory {}", path, unreadable);
            }
        }
    }

    private static void readConfigFromJson(String modId, JsonObject config, ClassLoader loader) {
        if (config == null) {
            return;
        }
        String pkg = config.has("package") ? config.get("package").getAsString() : "";
        for (String section : List.of("mixins", "client", "server")) {
            if (!config.has(section)) {
                continue;
            }
            for (var element : config.getAsJsonArray(section)) {
                String simpleName = element.getAsString();
                String className = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
                inspect(modId, className, loader);
            }
        }
    }

    private static void readConfig(String modId, JarFile jar, String configName, ClassLoader loader) {
        try (var stream = jar.getInputStream(jar.getEntry(configName))) {
            JsonObject config = JSON.fromJson(new String(stream.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
            if (config == null) {
                return;
            }
            readConfigFromJson(modId, config, loader);
        } catch (Exception malformed) {
            LOGGER.debug("[CompatGuard] Could not read mixin config {} from {}", configName, modId, malformed);
        }
    }

    private static void inspect(String modId, String className, ClassLoader loader) {
        scannedClasses++;
        Class<?> mixinClass;
        Mixin annotation;
        try {
            mixinClass = Class.forName(className, false, loader);
            annotation = mixinClass.getDeclaredAnnotation(Mixin.class);
        } catch (Throwable unresolved) {
            return;
        }
        if (annotation == null) {
            return;
        }

        List<String> targets = new ArrayList<>();
        try {
            for (Class<?> value : annotation.value()) {
                targets.add(value.getName());
            }
        } catch (Throwable unresolvedValue) {
            // A @Mixin(value = X.class) whose X cannot be resolved is fine: targets= still works.
        }
        targets.addAll(Arrays.asList(annotation.targets()));
        if (targets.isEmpty() || targets.stream().noneMatch(MixinAudit::isOurTarget)) {
            return;
        }

        List<String> injections = new ArrayList<>();
        try {
            for (var method : mixinClass.getDeclaredMethods()) {
                Inject inject = method.getAnnotation(Inject.class);
                if (inject != null) {
                    String points = Arrays.stream(inject.at())
                            .map(at -> at.value() + (at.slice().isEmpty() ? "" : "/" + String.join(",", at.slice())))
                            .reduce((left, right) -> left + "+" + right)
                            .orElse("?");
                    injections.add("Inject " + String.join(",", inject.method())
                            + " @ " + points + (inject.cancellable() ? " cancellable" : ""));
                }
                ModifyReturnValue modifyReturn = method.getAnnotation(ModifyReturnValue.class);
                if (modifyReturn != null) {
                    injections.add("ModifyReturnValue " + String.join(",", modifyReturn.method()));
                }
            }
        } catch (Throwable unreadable) {
            injections.add("annotations unreadable: " + unreadable.getClass().getSimpleName());
        }

        FOUND.put(modId + "|" + className, new ForeignMixin(
                modId, className, List.copyOf(targets), annotation.priority(), List.copyOf(injections)));
    }

    public static List<String> conflictReport() {
        List<String> lines = new ArrayList<>();
        lines.add("scan: jars=" + scannedJars + " classes=" + scannedClasses
                + " relevant=" + FOUND.size()
                + " state=" + (complete ? "complete" : (scanning ? "scanning" : "idle"))
                + (failure == null ? "" : " (" + failure + ")"));
        List<ConflictMatcher.Foreign> foreign = new ArrayList<>();
        for (ForeignMixin found : FOUND.values()) {
            foreign.add(new ConflictMatcher.Foreign(found.modId(), found.mixinClass(),
                    found.targetClasses(), found.priority(), found.injections()));
        }
        List<ConflictMatcher.Target> targets = new ArrayList<>();
        for (MixinAudit.MixinTarget target : MixinAudit.targets()) {
            targets.add(new ConflictMatcher.Target(target.id(), target.className(), target.method()));
        }
        lines.addAll(ConflictMatcher.report(foreign, targets));
        return lines;
    }
}
