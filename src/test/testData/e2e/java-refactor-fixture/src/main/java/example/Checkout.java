package example;

public final class Checkout {
    public int charge() {
        Invoice invoice = new Invoice(100);
        Customer customer = new Customer(10);
        return invoice.applyDiscount(customer);
    }
}
