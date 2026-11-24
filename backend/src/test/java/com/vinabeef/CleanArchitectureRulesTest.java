package com.vinabeef;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dependency rule, checked on the source: code inside points at nothing outside.
 *
 * <pre>
 *   web controllers, JDBC / JPA / MQTT / Spring Security adapters      (outside: they know the frameworks)
 *        |  depend on
 *        v
 *   use cases and rules  +  ports (interfaces the use cases own)      (inside: they know nothing about frameworks)
 * </pre>
 *
 * <p>It reads imports, so it needs no extra library and runs with the other unit tests. What counts as "inside" is
 * listed in {@link #CORE} plus every file in a {@code port} package. Entities (Gate, Movement, PresenceState, ...) keep
 * their JPA annotations on purpose: they are what the ports hand around, and splitting each into a plain class and a
 * mapped class would double the model without protecting any rule. The annotations are the only framework code in them.
 *
 * <p>Two lists below are debts, not permissions. {@link #CONTROLLERS_WITH_SQL} names the controllers that still run SQL
 * themselves; a controller that is cleaned must be taken off the list (the test fails until it is), and a new controller
 * that imports JDBC fails the test at once.
 */
class CleanArchitectureRulesTest {

    /** Use cases and rules. Every file in a {@code port} package is checked as well. */
    private static final Set<String> CORE = Set.of(
            "movement/SafetrustProcessingService", "movement/CredentialCardResolver", "movement/CrossSourceMatcher",
            "movement/GateCrossingResolver", "movement/GateDirectionPolicy",
            "ingestion/DeadLetterService", "ingestion/EdgeBatchReplay", "ingestion/IngestionHealthService",
            "ingestion/IngestionBenchmarkService", "ingestion/TelemetryEventProcessor", "ingestion/SafetrustEventDto",
            "security/RbacScopeService", "security/FarmAssignmentService", "security/CurrentScopeProvider",
            "security/BarnAccessPolicy", "security/UserScope",
            "facilities/GateDirectionMode",
            "web/GateReaderPlan", "web/UserChangeGuard", "web/SortSpec");

    /** The only framework imports a use case may have: annotations that carry no behaviour of their own. */
    private static final Set<String> ANNOTATION_ONLY = Set.of(
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Component",
            "org.springframework.beans.factory.annotation.Value",
            "org.springframework.transaction.annotation.Transactional",
            "org.springframework.transaction.annotation.Propagation",
            "com.fasterxml.jackson.annotation.JsonIgnore",
            "com.fasterxml.jackson.annotation.JsonIgnoreProperties",
            "com.fasterxml.jackson.annotation.JsonProperty");

    /** Imports that mean "this code knows how something is stored, sent or shown". */
    private static final List<String> OUTSIDE = List.of(
            "org.springframework.", "jakarta.persistence", "jakarta.servlet", "org.hibernate", "javax.sql", "java.sql",
            "org.eclipse.paho", "tools.jackson", "com.fasterxml.jackson.databind", "com.vinabeef.web.");

    /** Debt: controllers that still run SQL themselves. Shrinks as use cases are extracted; never grows. */
    private static final Set<String> CONTROLLERS_WITH_SQL = Set.of(
            "AlertController", "AuthController", "BatchIngestionController", "CattleManagementController",
            "GateMonitorController", "GateReaderController", "ManagementController", "MobileFieldController",
            "ReportController", "SurveillanceReportController", "UserManagementController");

    /** Spring Data repositories that live in their module's root package. New ones belong in an {@code adapter} package. */
    private static final Set<String> REPOSITORIES_IN_MODULE_ROOT = Set.of(
            "cattle/TagAssignmentRepository", "facilities/BarnRepository", "facilities/GateRepository",
            "movement/MovementRepository", "movement/PresenceStateRepository", "security/UserRepository");

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+?)(?:\\.\\*)?;", Pattern.MULTILINE);

    private static final Path MAIN = locateMain();

    private static Path locateMain() {
        for (String candidate : List.of("src/main/java/com/vinabeef", "backend/src/main/java/com/vinabeef")) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException("cannot find src/main/java/com/vinabeef from " + Path.of("").toAbsolutePath());
    }

    /** Relative path without ".java", with "/" separators: {@code movement/adapter/JdbcAlertSink}. */
    private static List<String> allMainClasses() {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(f -> f.toString().endsWith(".java"))
                    .map(f -> MAIN.relativize(f).toString().replace('\\', '/').replaceAll("\\.java$", ""))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> importsOf(String cls) {
        try {
            String source = Files.readString(MAIN.resolve(cls + ".java"));
            List<String> found = new ArrayList<>();
            Matcher m = IMPORT.matcher(source);
            while (m.find()) {
                found.add(m.group(1));
            }
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isPort(String cls) {
        return cls.contains("/port/");
    }

    private static boolean isAdapter(String cls) {
        return cls.contains("/adapter/");
    }

    private static List<String> inside() {
        return allMainClasses().stream().filter(c -> CORE.contains(c) || isPort(c)).toList();
    }

    @Test
    void everyClassNamedAsInsideExists() {
        List<String> all = allMainClasses();
        List<String> missing = CORE.stream().filter(c -> !all.contains(c)).sorted().toList();
        assertTrue(missing.isEmpty(), "CORE names classes that are gone (renamed or moved?), so the rules would check nothing: " + missing);
        assertTrue(all.stream().filter(CleanArchitectureRulesTest::isPort).count() >= 14, "the ports were not found");
    }

    @Test
    void useCasesAndRulesImportNoFrameworkBesidesAnnotations() {
        List<String> offences = new ArrayList<>();
        for (String cls : inside()) {
            for (String imp : importsOf(cls)) {
                boolean outside = OUTSIDE.stream().anyMatch(imp::startsWith);
                if (outside && !ANNOTATION_ONLY.contains(imp)) {
                    offences.add(cls + " imports " + imp);
                }
            }
        }
        assertTrue(offences.isEmpty(),
                "Inner code must reach storage, messaging and the web through a port. Offences:\n  " + String.join("\n  ", offences));
    }

    @Test
    void useCasesNameThePortsNeverTheAdaptersThatImplementThem() {
        List<String> offences = new ArrayList<>();
        for (String cls : inside()) {
            for (String imp : importsOf(cls)) {
                if (imp.contains(".adapter.")) {
                    offences.add(cls + " imports " + imp);
                }
            }
        }
        assertTrue(offences.isEmpty(), "Offences:\n  " + String.join("\n  ", offences));
    }

    @Test
    void aPortIsAnInterface() {
        List<String> offences = new ArrayList<>();
        for (String cls : allMainClasses()) {
            if (!isPort(cls)) {
                continue;
            }
            try {
                String source = Files.readString(MAIN.resolve(cls + ".java"));
                if (!source.contains("public interface ")) {
                    offences.add(cls);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertTrue(offences.isEmpty(), "A port package holds interfaces only: " + offences);
    }

    @Test
    void ingestionDoesNotKnowMovementSoTheDependencyPointsOneWay() {
        List<String> offences = new ArrayList<>();
        for (String cls : allMainClasses()) {
            if (cls.startsWith("ingestion/")) {
                importsOf(cls).stream().filter(i -> i.startsWith("com.vinabeef.movement.")).forEach(i -> offences.add(cls + " imports " + i));
            }
        }
        assertTrue(offences.isEmpty(), "movement implements ingestion's port, not the other way round: " + offences);
    }

    @Test
    void nothingOutsideTheWebPackageKnowsTheWebPackage() {
        List<String> offences = new ArrayList<>();
        for (String cls : allMainClasses()) {
            if (!cls.startsWith("web/")) {
                importsOf(cls).stream().filter(i -> i.startsWith("com.vinabeef.web.")).forEach(i -> offences.add(cls + " imports " + i));
            }
        }
        assertTrue(offences.isEmpty(), "Offences: " + offences);
    }

    @Test
    void theControllersThatStillRunSqlAreExactlyTheOnesOnTheDebtList() {
        Set<String> actual = new TreeSet<>();
        for (String cls : allMainClasses()) {
            if (cls.startsWith("web/") && importsOf(cls).stream().anyMatch(i -> i.startsWith("org.springframework.jdbc"))) {
                actual.add(cls.substring("web/".length()));
            }
        }
        assertEquals(new TreeSet<>(CONTROLLERS_WITH_SQL), actual,
                "A controller that imports JDBC must get a use case instead. If one was cleaned, take it off CONTROLLERS_WITH_SQL.");
    }

    @Test
    void newSpringDataRepositoriesGoInAnAdapterPackage() {
        Set<String> actual = new TreeSet<>();
        for (String cls : allMainClasses()) {
            if (!isAdapter(cls) && importsOf(cls).stream().anyMatch(i -> i.startsWith("org.springframework.data."))) {
                actual.add(cls);
            }
        }
        assertEquals(new TreeSet<>(REPOSITORIES_IN_MODULE_ROOT), actual,
                "Put a new Spring Data repository in the adapter package of its module, behind a port.");
    }
}
