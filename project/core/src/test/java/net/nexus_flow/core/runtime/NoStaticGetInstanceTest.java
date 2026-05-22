package net.nexus_flow.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.nexus_flow.core.cqrs.command.CommandBus;
import org.junit.jupiter.api.Test;

/**
 * architecture test — scans every {@link PerRuntime}-marked class under {@code net.nexus_flow.core}
 * and asserts that:
 *
 * <ul>
 * <li>It declares no {@code public static getInstance()} method (any arity, any return type).
 * <li>It declares no {@code public static} field literally named {@code INSTANCE} (case-sensitive
 * — see {@link PerRuntime}'s javadoc for the rationale).
 * </ul>
 *
 * <p>This is the regression guard required by thebrief: any future contributor that reintroduces a
 * process-wide bus singleton or a registry factory accessor fails the build here. Sealed-value
 * types such as {@code ErrorPolicy.FailFast#INSTANCE} are NOT marked {@code @PerRuntime} and are
 * therefore (correctly) ignored.
 */
class NoStaticGetInstanceTest {

    private static final String CORE_PACKAGE = "net.nexus_flow.core";

    @Test
    void noPerRuntimeClass_exposes_staticGetInstance_or_publicStaticInstanceField() throws IOException, URISyntaxException, ClassNotFoundException {

        List<Class<?>> perRuntimeClasses = scanPerRuntimeClasses();
        // Sanity: the scanner must actually find the well-known
        // @PerRuntime classes; otherwise a future packaging change
        // would silently disarm this test.
        assertFalse(
                    perRuntimeClasses.isEmpty(), "Scanner must discover at least one @PerRuntime class");
        assertTrue(
                   perRuntimeClasses.contains(CommandBus.class),
                   "Scanner must discover CommandBus as a @PerRuntime marker class; "
                           + "found: "
                           + perRuntimeClasses);

        List<String> violations = new ArrayList<>();
        for (Class<?> klass : perRuntimeClasses) {
            for (Method m : klass.getDeclaredMethods()) {
                int mods = m.getModifiers();
                if (Modifier.isStatic(mods) && Modifier.isPublic(mods) && "getInstance".equals(m.getName())) {
                    violations.add(
                                   "public static method "
                                           + klass.getName()
                                           + "#getInstance() reintroduces a process-wide singleton accessor");
                }
            }
            for (Field f : klass.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (Modifier.isStatic(mods) && Modifier.isPublic(mods) && "INSTANCE".equals(f.getName())) {
                    violations.add(
                                   "public static field "
                                           + klass.getName()
                                           + ".INSTANCE reintroduces a process-wide singleton handle");
                }
            }
        }

        assertTrue(
                   violations.isEmpty(),
                   " @PerRuntime classes must not expose static "
                           + "singleton accessors. Violations: "
                           + violations);
    }

    // ------------------------------------------------------------------
    // Classpath scanner — works for both exploded class dirs (Gradle
    // build/classes/...) and JAR-packaged classpath entries.
    // ------------------------------------------------------------------

    private static List<Class<?>> scanPerRuntimeClasses() throws IOException, URISyntaxException {
        Set<String> classNames   = new HashSet<>();
        String      resourcePath = CORE_PACKAGE.replace('.', '/');
        ClassLoader cl           = Thread.currentThread().getContextClassLoader();
        var         resources    = cl.getResources(resourcePath);
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            switch (url.getProtocol()) {
                case "file" -> collectFromFileSystem(Paths.get(url.toURI()), CORE_PACKAGE, classNames);
                case "jar"  -> collectFromJar(url, resourcePath, classNames);
                default     -> {
                    /* unknown protocol — skip */
                }
            }
        }
        assertNotNull(classNames);
        List<Class<?>> annotated = new ArrayList<>();
        for (String name : classNames) {
            Class<?> klass;
            try {
                klass = Class.forName(name, false, cl);
            } catch (Throwable ignored) {
                // Defensive: a class that fails to initialise (e.g.
                // missing optional dep) cannot violate the contract
                // since it cannot be a singleton accessor either.
                continue;
            }
            if (klass.isAnnotationPresent(PerRuntime.class)) {
                annotated.add(klass);
            }
        }
        return annotated;
    }

    private static void collectFromFileSystem(Path root, String packageName, Set<String> out) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                    .forEach(
                             p -> {
                                 Path rel = root.relativize(p);
                                 String suffix = rel.toString().replace(java.io.File.separatorChar, '.');
                                 suffix = suffix.substring(0, suffix.length() - ".class".length());
                                 // Skip inner-class synthetic names; reflection
                                 // still discovers them through the enclosing
                                 // class's getDeclaredClasses() if needed.
                                 if (suffix.contains("$")) {
                                     return;
                                 }
                                 out.add(packageName + "." + suffix);
                             });
        }
    }

    private static void collectFromJar(URL jarUrl, String resourcePath, Set<String> out) throws IOException {
        URI jarUri = URI.create(jarUrl.toString().substring(0, jarUrl.toString().indexOf('!')) + "!/");
        try (var fs = FileSystems.newFileSystem(jarUri, java.util.Map.of())) {
            Path root = fs.getPath("/" + resourcePath);
            if (!Files.exists(root)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".class"))
                        .forEach(
                                 p -> {
                                     String full = p.toString();
                                     if (full.startsWith("/")) {
                                         full = full.substring(1);
                                     }
                                     String name =
                                             full.replace('/', '.').substring(0, full.length() - ".class".length());
                                     if (name.contains("$")) {
                                         return;
                                     }
                                     out.add(name);
                                 });
            }
        }
    }
}
