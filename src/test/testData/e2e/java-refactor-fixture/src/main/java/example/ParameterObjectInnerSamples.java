package example;

public class ParameterObjectInnerSamples {
    public void createInvoice(String customer, String currency, int dueDays, boolean preview) {
        System.out.println(customer + currency + dueDays + preview);
        String assigned = currency;
        assigned = assigned.trim();
        System.out.println(assigned);
        if (preview) {
            System.out.println("inner preview");
        }
    }
}
