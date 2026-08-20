package example;

public class ConversionSamples {
    public static int discount(Customer customer, int amount) {
        return amount - customer.getRate();
    }

    public static String normalize(String raw) {
        return raw.trim().toLowerCase();
    }

    public static class Customer {
        private final int rate;

        public Customer(int rate) {
            this.rate = rate;
        }

        public int getRate() {
            return rate;
        }
    }
}
