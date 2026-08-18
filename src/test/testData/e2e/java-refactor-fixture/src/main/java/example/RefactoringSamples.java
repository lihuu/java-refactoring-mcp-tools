package example;

public final class RefactoringSamples {
    public int mutableCount;

    public int calculateTotal(int quantity, int unitPrice, boolean preferredCustomer) {
        int subtotal = quantity * unitPrice;
        int shipping = subtotal >= 100 ? 0 : 12;
        int discount = preferredCustomer ? subtotal / 10 : 0;
        return subtotal - discount + shipping;
    }

    public String formatLabel(String name) {
        String prefix = "order-";
        return prefix + name;
    }

    public int calculateTax(int amount) {
        return amount * 12 / 100;
    }

    private void unusedHelper() {
    }
}
