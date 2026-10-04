package testkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs every {@link Test} method of the given classes.
 *
 * Output lines (parsed by the lab server):
 *   PASS|Class|name|millis
 *   FAIL|Class|name|millis|message
 *   RESULT|passed|failed|total
 */
public final class TestRunner {

    public static void main(String[] args) throws Exception {
        int passed = 0;
        int failed = 0;
        for (String className : args) {
            Class<?> type = Class.forName(className);
            List<Method> tests = new ArrayList<>();
            for (Method m : type.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Test.class) && m.getParameterCount() == 0
                        && !Modifier.isStatic(m.getModifiers())) {
                    tests.add(m);
                }
            }
            tests.sort(Comparator.comparing(Method::getName));
            for (Method m : tests) {
                Test meta = m.getAnnotation(Test.class);
                String name = meta.value().isEmpty() ? m.getName() : meta.value();
                long start = System.nanoTime();
                String error = run(type, m, meta.timeoutMillis());
                long millis = (System.nanoTime() - start) / 1_000_000;
                if (error == null) {
                    passed++;
                    System.out.println("PASS|" + type.getSimpleName() + "|" + name + "|" + millis);
                } else {
                    failed++;
                    System.out.println("FAIL|" + type.getSimpleName() + "|" + name + "|" + millis + "|"
                            + error.replace('\n', ' ').replace('\r', ' '));
                }
                System.out.flush();
            }
        }
        System.out.println("RESULT|" + passed + "|" + failed + "|" + (passed + failed));
        System.out.flush();
        // Leaked non-daemon threads from user code must not keep the JVM alive.
        System.exit(failed == 0 ? 0 : 1);
    }

    private static String run(Class<?> type, Method method, long timeoutMillis) {
        FutureTask<Void> task = new FutureTask<>(() -> {
            Object instance = type.getDeclaredConstructor().newInstance();
            method.setAccessible(true);
            method.invoke(instance);
            return null;
        });
        Thread worker = new Thread(task, "test-" + method.getName());
        worker.setDaemon(true);
        worker.start();
        try {
            task.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return null;
        } catch (TimeoutException e) {
            worker.interrupt();
            return "timed out after " + timeoutMillis + " ms (deadlock or infinite loop?)";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InvocationTargetException ite && ite.getCause() != null) {
                cause = ite.getCause();
            }
            return describe(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    private static String describe(Throwable t) {
        if (t instanceof AssertionError) {
            return t.getMessage() == null ? "assertion failed" : t.getMessage();
        }
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        for (StackTraceElement el : t.getStackTrace()) {
            if (!el.getClassName().startsWith("java.") && !el.getClassName().startsWith("jdk.")
                    && !el.getClassName().startsWith("testkit.")) {
                sb.append(" at ").append(el.getClassName()).append('.').append(el.getMethodName())
                        .append(':').append(el.getLineNumber());
                break;
            }
        }
        return sb.toString();
    }
}
