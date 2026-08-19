package example;

public class Invoice {
    private final int amount;

    public Invoice(int amount) {
        this.amount = amount;
    }

    public int applyDiscount(Customer customer) {
        return this.amount - customer.discount();
    }

    public static class Customer {
        private final int discountRate;

        public Customer(int discountRate) {
            this.discountRate = discountRate;
        }

        public int discount() {
            return discountRate;
        }
    }
}
