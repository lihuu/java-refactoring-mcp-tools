package example;

public final class Customer {
    private final int discount;

    public Customer(int discount) {
        this.discount = discount;
    }

    public int discount() {
        return discount;
    }
}
