package imaging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import testkit.Test;

import static testkit.Assert.*;

public class PipelineTest {

    static Image img(String name, int w, int h, int fill) {
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, fill);
        return new Image(name, w, h, px);
    }

    static Transform sleep(long ms) {
        return in -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return in;
        };
    }

    @Test("invert and brighten (with clamping)")
    public void pixelTransforms() {
        Image in = new Image("a", 3, 1, new int[]{0, 100, 250});
        assertEquals(List.of(255, 155, 5), toList(Transforms.invert().apply(in).pixels()));
        assertEquals(List.of(10, 110, 255), toList(Transforms.brighten(10).apply(in).pixels()));
        assertEquals(List.of(0, 0, 150), toList(Transforms.brighten(-100).apply(in).pixels()));
        assertEquals(List.of(0, 100, 250), toList(in.pixels()), "input must not be mutated");
    }

    @Test("flipHorizontal mirrors each row")
    public void flip() {
        Image in = new Image("f", 3, 2, new int[]{1, 2, 3, 4, 5, 6});
        Image out = Transforms.flipHorizontal().apply(in);
        assertEquals(List.of(3, 2, 1, 6, 5, 4), toList(out.pixels()));
        assertEquals(3, out.width());
        assertEquals(2, out.height());
        assertEquals("f", out.name());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), toList(in.pixels()), "input must not be mutated");
    }

    @Test("chain applies in order and results keep input order")
    public void orderPreserved() throws Exception {
        Random rnd = new Random(7);
        List<Image> inputs = new ArrayList<>();
        for (int i = 0; i < 30; i++) inputs.add(img("img" + i, 4, 4, i));
        Transform jitter = in -> sleep(rnd.nextInt(20)).apply(in);
        List<Result> results = new Pipeline(6, 10_000).process(inputs,
                List.of(jitter, Transforms.brighten(100), Transforms.invert()));
        assertEquals(30, results.size());
        for (int i = 0; i < 30; i++) {
            Result r = results.get(i);
            assertTrue(r.ok(), "img" + i + " failed: " + r.error());
            assertEquals("img" + i, r.name());
            assertEquals(255 - Math.min(255, i + 100), r.image().pixels()[0], "img" + i + " value");
        }
    }

    @Test("one failing image does not affect the others")
    public void failureIsolation() throws Exception {
        List<Image> inputs = List.of(img("ok1", 2, 2, 1), img("boom", 2, 2, 2), img("ok2", 2, 2, 3));
        Transform explode = in -> {
            if (in.name().equals("boom")) throw new IllegalStateException("corrupt header");
            return in;
        };
        List<Result> results = new Pipeline(2, 1_000).process(inputs, List.of(explode, Transforms.invert()));
        assertTrue(results.get(0).ok());
        assertFalse(results.get(1).ok());
        assertTrue(results.get(1).error().contains("corrupt header"), "error message kept: " + results.get(1).error());
        assertEquals("boom", results.get(1).name());
        assertTrue(results.get(2).ok());
        assertEquals(252, results.get(2).image().pixels()[0]);
    }

    @Test("failures release their memory (no permit leak)")
    public void failuresReleaseMemory() throws Exception {
        List<Image> inputs = new ArrayList<>();
        for (int i = 0; i < 10; i++) inputs.add(img("x" + i, 10, 10, 0)); // 400 bytes each
        Transform fail = in -> {
            throw new RuntimeException("nope");
        };
        // budget fits exactly one image: if failures leak permits, this deadlocks (and times out)
        List<Result> results = new Pipeline(2, 400).process(inputs, List.of(fail));
        assertEquals(10, results.size());
        for (Result r : results) assertFalse(r.ok());
    }

    @Test("memory budget is never exceeded")
    public void budget() throws Exception {
        AtomicLong inFlight = new AtomicLong();
        AtomicLong peak = new AtomicLong();
        Transform track = in -> {
            long now = inFlight.addAndGet(in.sizeBytes());
            peak.accumulateAndGet(now, Math::max);
            sleep(15).apply(in);
            inFlight.addAndGet(-in.sizeBytes());
            return in;
        };
        List<Image> inputs = new ArrayList<>();
        int[] sides = {10, 5, 20, 8, 15, 3, 12, 20, 6, 9, 18, 4};
        for (int i = 0; i < sides.length; i++) inputs.add(img("i" + i, sides[i], sides[i], 0));
        long budget = 2_000; // 20x20 image = 1600 bytes
        List<Result> results = new Pipeline(8, budget).process(inputs, List.of(track));
        for (Result r : results) assertTrue(r.ok(), r.name() + ": " + r.error());
        assertTrue(peak.get() <= budget, "peak in-flight bytes " + peak.get() + " > budget " + budget);
        assertTrue(peak.get() > 1600, "pipeline should still run several images at once, peak=" + peak.get());
    }

    @Test("images larger than the budget fail fast with a clear error")
    public void tooLarge() throws Exception {
        List<Image> inputs = List.of(img("small", 2, 2, 0), img("huge", 100, 100, 0), img("small2", 2, 2, 0));
        List<Result> results = new Pipeline(2, 1_000).process(inputs, List.of(Transforms.invert()));
        assertTrue(results.get(0).ok());
        assertFalse(results.get(1).ok());
        assertTrue(results.get(1).error().contains("exceeds memory budget"), results.get(1).error());
        assertTrue(results.get(2).ok());
    }

    @Test("admission is FIFO: small images do not overtake a waiting large one")
    public void fifo() throws Exception {
        List<String> started = Collections.synchronizedList(new ArrayList<>());
        Transform record = in -> {
            started.add(in.name());
            return sleep(40).apply(in);
        };
        // budget 1000. A(600) runs; B(800) must wait for A; C(40) and D(40) would fit beside A
        // but must not start before B.
        List<Image> inputs = List.of(img("A", 15, 10, 0), img("B", 20, 10, 0), img("C", 2, 5, 0), img("D", 2, 5, 0));
        List<Result> results = new Pipeline(4, 1_000).process(inputs, List.of(record));
        for (Result r : results) assertTrue(r.ok());
        assertEquals(List.of("A", "B", "C", "D"), List.copyOf(started), "start order");
    }

    @Test("uses multiple workers")
    public void parallelism() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Transform t = in -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            sleep(30).apply(in);
            active.decrementAndGet();
            return in;
        };
        List<Image> inputs = new ArrayList<>();
        for (int i = 0; i < 12; i++) inputs.add(img("p" + i, 2, 2, 0));
        long start = System.nanoTime();
        new Pipeline(4, 100_000).process(inputs, List.of(t));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(peak.get() > 1 && peak.get() <= 4, "peak workers " + peak.get());
        assertTrue(ms < 12 * 30, "should be faster than sequential, took " + ms + "ms");
    }

    @Test("validates constructor")
    public void validation() {
        assertThrows(IllegalArgumentException.class, () -> new Pipeline(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new Pipeline(1, 0));
    }

    static List<Integer> toList(int[] a) {
        List<Integer> out = new ArrayList<>();
        for (int v : a) out.add(v);
        return out;
    }
}
