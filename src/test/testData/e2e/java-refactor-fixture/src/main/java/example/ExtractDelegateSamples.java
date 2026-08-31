package example;

public class ExtractDelegateSamples {
    public double unitPrice = 2.5;
    public double price(int quantity) {
        return quantity * unitPrice;
    }
    public double total() {
        return price(2) - 1;
    }
}