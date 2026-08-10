package mcp;

final class ComplexMethod {
    int calculateTotal(int quantity, int unitPrice, boolean preferredCustomer) {
        int subtotal = quantity * unitPrice;
        int shipping = subtotal >= 100 ? 0 : 10;

        int discount = preferredCustomer ? subtotal / 10 : 0;
        int taxed = subtotal - discount;

        return taxed + shipping;
    }
}
