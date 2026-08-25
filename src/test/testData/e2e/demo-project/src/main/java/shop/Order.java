package shop;

public class Order {
    private final Customer customer;
    private final double amount;

    public Order(Customer customer, double amount) {
        this.customer = customer;
        this.amount = amount;
    }

    public Customer getCustomer() {
        return customer;
    }

    public double getAmount() {
        return amount;
    }

    public void printShippingLabel() {
        System.out.println("Ship to: " + customer.name + " <" + customer.email + ">");
    }
}
