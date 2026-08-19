package example;

public final class Checkout {
    public int charge() {
        Invoice invoice = new Invoice(100);
        Invoice.Customer customer = new Invoice.Customer(10);
        return invoice.applyDiscount(customer);
    }
}
