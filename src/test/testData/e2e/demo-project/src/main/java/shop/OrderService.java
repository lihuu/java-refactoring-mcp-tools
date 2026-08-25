package shop;

public class OrderService {

    public double placeOrder(Order order) {
        double base = order.getAmount();
        if (base > 1000) {
            base = base * 0.9;
        }
        double tax = base * 0.19;
        double shipping;
        if (base >= 50) {
            shipping = 0;
        } else {
            shipping = 4.95;
        }
        double total = base + tax + shipping;
        order.getCustomer().addPoints((int) (total / 10));
        System.out.println("order placed, total = " + total);
        return total;
    }

    public double quote(Order order) {
        double base = order.getAmount();
        if (base > 1000) {
            base = base * 0.9;
        }
        return base + base * 0.19;
    }

    public void legacyNotify(String email) {
        System.out.println("notify " + email);
    }
}
