package returns;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import testkit.Test;

import static testkit.Assert.*;

public class ReturnRiskEngineTest {

    final ReturnRiskEngine engine = new ReturnRiskEngine();

    /** A request that triggers no rules at all. */
    static ReturnRequest clean() {
        return new ReturnRequest("o1", 1_000, 5, 0, Category.BOOKS, false, true);
    }

    static ReturnRequest days(int d) {
        return new ReturnRequest("o", 1_000, d, 0, Category.BOOKS, false, true);
    }

    static ReturnRequest amount(long cents) {
        return new ReturnRequest("o", cents, 5, 0, Category.BOOKS, false, true);
    }

    static ReturnRequest returns(int n) {
        return new ReturnRequest("o", 1_000, 5, n, Category.BOOKS, false, true);
    }

    @Test("a clean request scores 0 / LOW with no reasons")
    public void cleanRequest() {
        RiskResult r = engine.assess(clean());
        assertEquals(0, r.score());
        assertEquals(RiskLevel.LOW, r.level());
        assertEquals(List.of(), r.reasons());
    }

    @Test("late-return boundaries: 30 / 31 / 60 / 61 days")
    public void lateBoundaries() {
        assertEquals(0, engine.assess(days(30)).score(), "30 days is not late");
        assertEquals(25, engine.assess(days(31)).score(), "31 days");
        assertEquals(25, engine.assess(days(60)).score(), "60 days is still the lower tier");
        assertEquals(40, engine.assess(days(61)).score(), "61 days: 40, not 25+40");
        assertEquals(List.of("LATE_RETURN"), engine.assess(days(90)).reasons());
    }

    @Test("amount boundaries: $499.99 / $500 / $999.99 / $1000")
    public void amountBoundaries() {
        assertEquals(0, engine.assess(amount(49_999)).score());
        assertEquals(20, engine.assess(amount(50_000)).score(), "exactly $500 counts");
        assertEquals(20, engine.assess(amount(99_999)).score());
        assertEquals(35, engine.assess(amount(100_000)).score(), "exactly $1000");
        assertEquals(List.of("HIGH_VALUE"), engine.assess(amount(100_000)).reasons());
    }

    @Test("frequent returner boundaries: 2 / 3 / 5 / 6")
    public void returnBoundaries() {
        assertEquals(0, engine.assess(returns(2)).score());
        assertEquals(15, engine.assess(returns(3)).score());
        assertEquals(15, engine.assess(returns(5)).score());
        assertEquals(30, engine.assess(returns(6)).score());
    }

    @Test("opened electronics needs both conditions")
    public void openedElectronics() {
        assertEquals(10, engine.assess(new ReturnRequest("o", 1_000, 5, 0, Category.ELECTRONICS, true, true)).score());
        assertEquals(0, engine.assess(new ReturnRequest("o", 1_000, 5, 0, Category.ELECTRONICS, false, true)).score(),
                "unopened electronics");
        assertEquals(0, engine.assess(new ReturnRequest("o", 1_000, 5, 0, Category.APPAREL, true, true)).score(),
                "opened apparel");
    }

    @Test("level boundaries: <30 LOW, 30 MEDIUM, 55 MEDIUM, 60 HIGH")
    public void levels() {
        // 25 (late) -> LOW
        assertEquals(RiskLevel.LOW, engine.assess(days(45)).level());
        // 20 + 10 = 30 -> MEDIUM
        RiskResult thirty = engine.assess(new ReturnRequest("o", 1_000, 5, 0, Category.ELECTRONICS, true, false));
        assertEquals(30, thirty.score());
        assertEquals(RiskLevel.MEDIUM, thirty.level());
        // 25 + 35 = 60 -> HIGH
        RiskResult sixty = engine.assess(new ReturnRequest("o", 100_000, 31, 0, Category.BOOKS, false, true));
        assertEquals(60, sixty.score());
        assertEquals(RiskLevel.HIGH, sixty.level(), "60 is HIGH");
        // 40 + 15 = 55 -> MEDIUM
        assertEquals(RiskLevel.MEDIUM, engine.assess(new ReturnRequest("o", 1_000, 61, 3, Category.BOOKS, false, true)).level());
    }

    @Test("score is capped at 100 and reasons are in rulebook order")
    public void capAndOrder() {
        RiskResult r = engine.assess(new ReturnRequest("o", 250_000, 90, 10, Category.ELECTRONICS, true, false));
        assertEquals(100, r.score(), "40+35+30+10+20 = 135, capped");
        assertEquals(RiskLevel.HIGH, r.level());
        assertEquals(List.of("LATE_RETURN", "HIGH_VALUE", "FREQUENT_RETURNER", "OPENED_ELECTRONICS", "NO_RECEIPT"),
                r.reasons());
    }

    @Test("validation")
    public void validation() {
        assertThrows(IllegalArgumentException.class, () -> engine.assess(null));
        assertThrows(IllegalArgumentException.class, () -> engine.assess(new ReturnRequest(" ", 1, 1, 0, Category.HOME, false, true)));
        assertThrows(IllegalArgumentException.class, () -> engine.assess(new ReturnRequest("o", -1, 1, 0, Category.HOME, false, true)));
        assertThrows(IllegalArgumentException.class, () -> engine.assess(new ReturnRequest("o", 1, -1, 0, Category.HOME, false, true)));
        assertThrows(IllegalArgumentException.class, () -> engine.assess(new ReturnRequest("o", 1, 1, -2, Category.HOME, false, true)));
        assertThrows(IllegalArgumentException.class, () -> engine.assess(new ReturnRequest("o", 1, 1, 0, null, false, true)));
    }

    @Test("batch keeps order and isolates bad rows")
    public void batch() {
        List<ReturnRequest> in = new ArrayList<>(Arrays.asList(
                new ReturnRequest("a", 1_000, 5, 0, Category.BOOKS, false, true),
                new ReturnRequest("b", -5, 5, 0, Category.BOOKS, false, true),
                null,
                new ReturnRequest("d", 60_000, 5, 0, Category.BOOKS, false, true)));
        List<BatchEntry> out = engine.assessBatch(in);
        assertEquals(4, out.size());
        assertTrue(out.get(0).isOk());
        assertEquals("a", out.get(0).orderId());
        assertFalse(out.get(1).isOk(), "negative amount row is an error entry");
        assertEquals("b", out.get(1).orderId());
        assertFalse(out.get(2).isOk(), "null row is an error entry");
        assertNull(out.get(2).orderId(), "null row has no order id");
        assertTrue(out.get(3).isOk());
        assertEquals(20, out.get(3).result().score());
    }

    @Test("returned reasons cannot be modified by the caller")
    public void immutableReasons() {
        RiskResult r = engine.assess(days(45));
        assertThrows(UnsupportedOperationException.class, () -> r.reasons().add("HACK"));
    }
}
