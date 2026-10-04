package returns;

public record BatchEntry(String orderId, RiskResult result, String error) {

    public static BatchEntry ok(String orderId, RiskResult result) {
        return new BatchEntry(orderId, result, null);
    }

    public static BatchEntry error(String orderId, String message) {
        return new BatchEntry(orderId, null, message == null ? "invalid request" : message);
    }

    public boolean isOk() {
        return error == null;
    }
}
