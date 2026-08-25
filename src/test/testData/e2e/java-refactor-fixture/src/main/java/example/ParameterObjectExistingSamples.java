package example;

public class ParameterObjectExistingSamples {
    public void createInvoice(String currency, int dueDays) {
        dueDays = Math.max(dueDays, 0);
        System.out.println(currency + dueDays);
    }

    public void createInvoiceWithCustomer(String customer, String currency, int dueDays) {
        System.out.println(customer + ":" + currency + ":" + dueDays);
        currency = currency.trim();
        System.out.println(currency);
    }
}
