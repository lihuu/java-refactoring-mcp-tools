package example;

public final class OrderService {
    private final int taxRate;

    public OrderService(int taxRate) {
        this.taxRate = taxRate;
    }

    public int netAmount(int amount) {
        return amount * (100 - taxRate) / 100;
    }

    public class LineFormatter {
        private final String prefix;

        public LineFormatter(String prefix) {
            this.prefix = prefix;
        }

        public String render(int amount) {
            return prefix + amount;
        }
    }
}
