package shop;

public class Checkout {
    private EmailNotifier notifier;

    public Checkout(EmailNotifier notifier) {
        this.notifier = notifier;
    }

    public void complete(Order order) {
        notifier.send("order total " + order.getAmount());
    }
}
