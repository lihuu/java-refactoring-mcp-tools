package example;

public class MethodObjectSamples {
    public double price(int quantity, double unit) {
        double subtotal = quantity * unit;
        double discount = quantity > 10 ? 0.1 : 0.0;
        return subtotal * (1 - discount);
    }
}
