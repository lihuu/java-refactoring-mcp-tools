package example;

public final class Invoice {
    private final int amount;

    public Invoice(int amount) {
        this.amount = amount;
    }

    public int applyDiscount(Customer customer) {
        return amount - customer.discount();
    }
}
