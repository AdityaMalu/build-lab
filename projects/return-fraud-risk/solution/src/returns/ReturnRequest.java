package returns;

public record ReturnRequest(
        String orderId,
        long amountCents,
        int daysSincePurchase,
        int returnsLast90Days,
        Category category,
        boolean opened,
        boolean hasReceipt) {
}
