package shop;

public class Main {
    public static void main(String[] args) {
        Customer customer = new Customer("Ada", "ada@example.com");
        Order order = new Order(customer, 1200);
        OrderService service = new OrderService();
        System.out.println(service.placeOrder(order));
        System.out.println(service.quote(order));
        order.printShippingLabel();
        Checkout checkout = new Checkout(new EmailNotifier());
        checkout.complete(order);
        System.out.println(new TaxPolicy().rateFor("DE"));
    }
}
