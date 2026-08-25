package example;

public class ParameterObjectTopLevelSamples {
    public void createInvoice(String customer, String currency, int dueDays, boolean preview) {
        System.out.println(customer + currency + dueDays + preview);
    }
}
