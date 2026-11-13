package com.afrotransact.platform.test.tenant;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.afrotransact.platform.security.StaffOrServiceOnly;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Route drift guard (TaxGateArchitectureTest style): every HTTP route of a service must be declared in
 * {@code tenant-isolation-matrix.txt} with an {@link IsolationClass}; a new route without a row fails the build.
 *
 * <p>Row format, one per line, {@code #} starts a comment, fields separated by {@code |}:
 * <pre>
 * GET /v1/offers/{id} | ORG_OWNED | OfferServiceImpl.get requireOwned(owner org) | OfferTenantIsolationTest
 * &#64;OfferAdminController | STAFF_ONLY | offers:manage + staff principal | -
 * </pre>
 * Rows starting with an at-sign and a controller class name cover every route of a controller, allowed only for classes where no org member
 * may reach tenant data (PUBLIC, STAFF_ONLY, SERVICE_ONLY, WEBHOOK). Classes that need a test must name a test class
 * that exists in the service; UNVERIFIED rows must say why.
 */
public final class TenantIsolationMatrix {

    /**
     * Marker in a row's enforcement column: the handler (or its controller) carries {@code @StaffOrServiceOnly}, the
     * principal-type guard on top of the permission check. The guard and the marker must agree in both directions.
     */
    public static final String PRINCIPAL_GUARD = "[principal-guard]";

    private TenantIsolationMatrix() {
    }

    /** Fails when the controllers under {@code basePackage} and the matrix resource disagree. */
    public static void assertComplete(String basePackage, String resource) {
        List<String> problems = new ArrayList<>();
        Map<String, Row> rows = new LinkedHashMap<>();
        Map<String, Row> controllers = new LinkedHashMap<>();
        load(resource, rows, controllers, problems);
        Set<String> testClasses = new LinkedHashSet<>();
        new ClassFileImporter().importPackages(basePackage).forEach(c -> testClasses.add(c.getSimpleName()));

        Set<String> seenRoutes = new TreeSet<>();
        Set<String> seenControllers = new TreeSet<>();
        for (Class<?> controller : controllers(basePackage)) {
            seenControllers.add(controller.getSimpleName());
            for (String route : routes(controller)) {
                seenRoutes.add(route);
                Row row = rows.get(route);
                Row wide = controllers.get(controller.getSimpleName());
                if (row == null && wide == null) {
                    problems.add("route not in the tenant-isolation matrix: " + route + " ("
                            + controller.getSimpleName() + "); declare its isolation class");
                }
                if (row != null && wide != null) {
                    problems.add("route declared twice (route row and controller row): " + route);
                }
                Row effective = row != null ? row : wide;
                if (effective != null) {
                    boolean marked = markedRoutes(controller).getOrDefault(route, false);
                    boolean declared = effective.enforcement.contains(PRINCIPAL_GUARD);
                    if (marked && !declared) {
                        problems.add(route + ": handler carries @StaffOrServiceOnly but the row does not say "
                                + PRINCIPAL_GUARD);
                    }
                    if (declared && !marked) {
                        problems.add(route + ": row says " + PRINCIPAL_GUARD + " but neither the handler nor "
                                + controller.getSimpleName() + " carries @StaffOrServiceOnly");
                    }
                }
            }
        }
        rows.forEach((route, row) -> {
            if (!seenRoutes.contains(route)) {
                problems.add("stale matrix row, no such route: " + route);
            }
        });
        controllers.forEach((name, row) -> {
            if (!seenControllers.contains(name)) {
                problems.add("stale matrix row, no such controller: @" + name);
            }
        });
        Map<String, Row> all = new LinkedHashMap<>(rows);
        controllers.forEach((k, v) -> all.put("@" + k, v));
        all.forEach((key, row) -> {
            if (row.cls.needsTest() && !testClasses.contains(row.test)) {
                problems.add(key + ": class " + row.cls + " needs an existing test class, got '" + row.test + "'");
            }
            if (row.enforcement.isBlank()) {
                problems.add(key + ": enforcement column is empty");
            }
            if (row.cls == IsolationClass.UNVERIFIED && row.enforcement.length() < 15) {
                problems.add(key + ": UNVERIFIED needs a reason");
            }
        });
        if (!problems.isEmpty()) {
            throw new AssertionError("Tenant isolation matrix problems (" + resource + "):\n - "
                    + String.join("\n - ", problems));
        }
    }

    /** Counts of routes per class, for reporting. */
    public static Map<IsolationClass, Integer> countByClass(String basePackage, String resource) {
        Map<IsolationClass, Integer> counts = new LinkedHashMap<>();
        Map<String, Row> rows = new LinkedHashMap<>();
        Map<String, Row> controllers = new LinkedHashMap<>();
        load(resource, rows, controllers, new ArrayList<>());
        for (Class<?> controller : controllers(basePackage)) {
            for (String route : routes(controller)) {
                Row row = rows.getOrDefault(route, controllers.get(controller.getSimpleName()));
                if (row != null) {
                    counts.merge(row.cls, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    static List<Class<?>> controllers(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<Class<?>> out = new ArrayList<>();
        for (BeanDefinition def : scanner.findCandidateComponents(basePackage)) {
            try {
                out.add(ClassUtils.forName(def.getBeanClassName(), TenantIsolationMatrix.class.getClassLoader()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    static Set<String> routes(Class<?> controller) {
        Set<String> out = new TreeSet<>();
        RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        List<String> prefixes = base == null || base.path().length == 0 ? List.of("") : List.of(base.path());
        for (Method m : controller.getDeclaredMethods()) {
            RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
            if (rm == null) {
                continue;
            }
            List<String> paths = rm.path().length == 0 ? List.of("") : List.of(rm.path());
            RequestMethod[] methods = rm.method().length == 0 ? RequestMethod.values() : rm.method();
            for (String prefix : prefixes) {
                for (String path : paths) {
                    for (RequestMethod method : methods) {
                        String full = (prefix + path).isEmpty() ? "/" : prefix + path;
                        out.add(method.name() + " " + full);
                    }
                }
            }
        }
        return out;
    }

    /** Route -> whether its handler method or controller class carries {@code @StaffOrServiceOnly}. */
    static Map<String, Boolean> markedRoutes(Class<?> controller) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        boolean classMarked = AnnotatedElementUtils.hasAnnotation(controller, StaffOrServiceOnly.class);
        RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        List<String> prefixes = base == null || base.path().length == 0 ? List.of("") : List.of(base.path());
        for (Method m : controller.getDeclaredMethods()) {
            RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
            if (rm == null) {
                continue;
            }
            boolean marked = classMarked || AnnotatedElementUtils.hasAnnotation(m, StaffOrServiceOnly.class);
            List<String> paths = rm.path().length == 0 ? List.of("") : List.of(rm.path());
            RequestMethod[] methods = rm.method().length == 0 ? RequestMethod.values() : rm.method();
            for (String prefix : prefixes) {
                for (String path : paths) {
                    for (RequestMethod method : methods) {
                        String full = (prefix + path).isEmpty() ? "/" : prefix + path;
                        out.merge(method.name() + " " + full, marked, (x, y) -> x && y);
                    }
                }
            }
        }
        return out;
    }

    private static void load(String resource, Map<String, Row> rows, Map<String, Row> controllers,
                             List<String> problems) {
        String text;
        try (InputStream in = TenantIsolationMatrix.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new AssertionError("Missing " + resource + " on the test classpath");
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        int n = 0;
        for (String line : text.split("\n")) {
            n++;
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            String[] f = l.split("\\|", -1);
            if (f.length < 4) {
                problems.add("line " + n + ": expected 'route | CLASS | enforcement | test'");
                continue;
            }
            IsolationClass cls;
            try {
                cls = IsolationClass.valueOf(f[1].strip());
            } catch (IllegalArgumentException e) {
                problems.add("line " + n + ": unknown isolation class " + f[1].strip());
                continue;
            }
            Row row = new Row(cls, f[2].strip(), f[3].strip());
            String key = f[0].strip();
            if (key.startsWith("@")) {
                if (!cls.controllerWide()) {
                    problems.add("line " + n + ": controller-wide rows are only for PUBLIC, STAFF_ONLY, SERVICE_ONLY, "
                            + "WEBHOOK; list " + cls + " routes one by one");
                }
                if (controllers.put(key.substring(1), row) != null) {
                    problems.add("line " + n + ": duplicate " + key);
                }
            } else if (rows.put(key.replaceAll("\\s+", " "), row) != null) {
                problems.add("line " + n + ": duplicate " + key);
            }
        }
    }

    private record Row(IsolationClass cls, String enforcement, String test) {
    }
}
